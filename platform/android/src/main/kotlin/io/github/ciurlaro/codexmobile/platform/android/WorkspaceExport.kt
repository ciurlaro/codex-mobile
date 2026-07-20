package io.github.ciurlaro.codexmobile.platform.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.ciurlaro.codexmobile.core.ApprovalPreview
import io.github.ciurlaro.codexmobile.core.DeviceTool
import io.github.ciurlaro.codexmobile.core.MutationRecord
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolDefinition
import io.github.ciurlaro.codexmobile.core.ToolEffect
import io.github.ciurlaro.codexmobile.core.ToolPlan
import io.github.ciurlaro.codexmobile.core.ToolRejectedException
import io.github.ciurlaro.codexmobile.core.ToolResult
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

internal class WorkspaceExportAuthority(
    context: Context,
    private val store: WorkspaceStore,
) {
    private val resolver = context.applicationContext.contentResolver
    private val preferences = context.applicationContext.getSharedPreferences(
        EXPORT_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()
    private val mutationMutex = Mutex()
    private val tool = ExportWorkspaceTool()

    val tools: List<DeviceTool> = listOf(tool)

    fun handles(toolName: String): Boolean = toolName == EXPORT_TOOL

    fun persist(treeUri: Uri, protectedUri: Uri?, protectedNeedsWrite: Boolean): ResourceScopeId {
        require(
            treeUri.scheme == ContentResolver.SCHEME_CONTENT &&
                treeUri.authority?.isNotBlank() == true && DocumentsContract.isTreeUri(treeUri),
        ) { "Select a document-provider folder" }
        val rootId = DocumentsContract.getTreeDocumentId(treeUri).takeIf(String::isNotBlank)
            ?: throw ToolRejectedException("Selected export folder is invalid")
        if (!queryOne(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId), rootId).isDirectory) {
            throw ToolRejectedException("Select an export folder, not a document")
        }

        synchronized(lock) {
            val previous = scopeOrNullLocked()
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver.takePersistableUriPermission(treeUri, flags)
            if (!hasGrant(treeUri, write = true)) {
                throw SecurityException("The selected provider did not persist writable access")
            }
            val id = ResourceScopeId(UUID.randomUUID().toString())
            check(
                preferences.edit().putString(ID_KEY, id.value).putString(URI_KEY, treeUri.toString()).commit(),
            ) { "Unable to save export folder" }
            if (previous != null && previous.treeUri != treeUri) {
                if (previous.treeUri == protectedUri) {
                    if (!protectedNeedsWrite) releaseWriteGrant(previous.treeUri)
                } else {
                    releaseGrant(previous.treeUri)
                }
            }
            return id
        }
    }

    fun currentScopeId(): ResourceScopeId? = synchronized(lock) { scopeOrNullLocked()?.id }

    fun currentTreeUri(): Uri? = synchronized(lock) { scopeOrNullLocked()?.treeUri }

    fun revoke(scopeId: ResourceScopeId, protectedUri: Uri?, protectedNeedsWrite: Boolean) = synchronized(lock) {
        val scope = scopeOrNullLocked()
            ?: throw ToolRejectedException("Export folder is unavailable")
        if (scope.id != scopeId) throw ToolRejectedException("Export folder does not match")
        check(preferences.edit().clear().commit()) { "Unable to revoke export folder" }
        if (scope.treeUri == protectedUri) {
            if (!protectedNeedsWrite) releaseWriteGrant(scope.treeUri)
        } else {
            releaseGrant(scope.treeUri)
        }
    }

    private fun scopeOrNullLocked(): Scope? {
        val id = preferences.getString(ID_KEY, null)?.takeIf(String::isNotBlank)
        val uri = preferences.getString(URI_KEY, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (id == null || uri == null || !hasGrant(uri, write = true)) {
            if (preferences.all.isNotEmpty()) preferences.edit().clear().commit()
            return null
        }
        return Scope(ResourceScopeId(id), uri)
    }

    private fun requireScope(scopeId: ResourceScopeId): Scope = synchronized(lock) {
        scopeOrNullLocked()?.takeIf { it.id == scopeId }
            ?: throw ToolRejectedException("Export folder is unavailable or changed")
    }

    private fun hasGrant(uri: Uri, write: Boolean): Boolean = resolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission && (!write || it.isWritePermission)
    }

    private fun releaseGrant(uri: Uri) {
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags != 0) runCatching { resolver.releasePersistableUriPermission(uri, flags) }
    }

    private fun releaseWriteGrant(uri: Uri) {
        if (resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) {
            runCatching {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    private inner class ExportWorkspaceTool : DeviceTool {
        private val pending = ConcurrentHashMap<String, ExportSnapshot>()

        override val definition = ToolDefinition(
            EXPORT_TOOL,
            "Export one private UTF-8 workspace file to the selected Android export folder. Existing text is replaced only after exact diff approval.",
            EXPORT_SCHEMA,
        )
        override val effect = ToolEffect.MUTATION

        override suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan =
            withContext(Dispatchers.IO) {
                val scope = requireScope(scopeId)
                val arguments = strictArguments(call.argumentsJson)
                val source = store.getById(arguments.getString(WORKSPACE_ID))
                    ?: throw ToolRejectedException("Private workspace file is unavailable")
                val destinationName = arguments.optString(DESTINATION_NAME)
                    .takeIf(String::isNotBlank) ?: source.path.substringAfterLast('/')
                validateName(destinationName)
                val root = root(scope)
                val matches = children(scope, root.id).filter { it.name == destinationName }
                if (matches.size > 1) throw ToolRejectedException("Export provider returned duplicate names")
                val existing = matches.singleOrNull()
                if (existing?.isDirectory == true) throw ToolRejectedException("Export name belongs to a folder")
                if (existing != null && existing.flags and Document.FLAG_SUPPORTS_WRITE == 0) {
                    throw ToolRejectedException("Existing export is not writable")
                }
                if (existing == null && root.flags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                    throw ToolRejectedException("Export provider cannot create documents here")
                }
                val before = existing?.let { readText(scope, it) }
                val diff = fullDiff(destinationName, before?.text, source.content)
                if (diff.toByteArray(StandardCharsets.UTF_8).size > MAX_DIFF_BYTES) {
                    throw ToolRejectedException("Export diff is too large to approve safely")
                }
                val snapshot = ExportSnapshot(
                    call = call,
                    scopeId = scopeId,
                    sourcePath = source.path,
                    sourceSha256 = source.sha256,
                    destinationName = destinationName,
                    rootId = root.id,
                    existing = existing,
                    existingSha256 = before?.sha256,
                    nextSha256 = source.content.toByteArray(StandardCharsets.UTF_8).sha256(),
                )
                val fingerprint = fingerprint(snapshot)
                if (pending.size >= MAX_PENDING || pending.putIfAbsent(fingerprint, snapshot) != null) {
                    throw ToolRejectedException("A matching export preview is already pending")
                }
                ToolPlan(
                    call,
                    scopeId,
                    effect,
                    "Export ${source.path} to Android",
                    fingerprint,
                    ApprovalPreview(
                        operation = if (existing == null) "Create exported file" else "Overwrite exported file",
                        source = "Private workspace / ${source.path}",
                        destination = "Export folder / $destinationName",
                        scope = "Only the selected Android export folder",
                        conflictBehavior = "Reject if the workspace source or destination changes after preview",
                        diff = diff,
                    ),
                )
            }

        override suspend fun execute(plan: ToolPlan): ToolResult {
            val snapshot = pending.remove(plan.fingerprint)
                ?: return ToolResult.Rejected(plan.call.id, "Export preview is unavailable or already used")
            if (snapshot.call != plan.call || snapshot.scopeId != plan.scopeId || plan.approvalPreview == null) {
                return ToolResult.Rejected(plan.call.id, "Export plan does not match its preview")
            }
            val dispatched = AtomicBoolean()
            return try {
                withContext(Dispatchers.IO) {
                    mutationMutex.withLock { executeExport(plan, snapshot, dispatched) }
                }
            } catch (error: CancellationException) {
                if (!dispatched.get()) throw error
                withContext(NonCancellable + Dispatchers.IO) { observe(plan.call, snapshot) }
            } catch (error: ToolRejectedException) {
                ToolResult.Rejected(plan.call.id, error.message ?: "Export preview became stale")
            } catch (_: SecurityException) {
                ToolResult.Failed(plan.call.id, "permission_denied", "Export folder permission is unavailable")
            } catch (_: FileNotFoundException) {
                ToolResult.Failed(plan.call.id, "not_found", "Export destination is unavailable")
            } catch (_: Exception) {
                if (dispatched.get()) observe(plan.call, snapshot)
                else ToolResult.Failed(plan.call.id, "export_failure", "Export failed before writing")
            }
        }

        override fun abandon(plan: ToolPlan) {
            pending.remove(plan.fingerprint)
        }

        override fun recoveryPayload(plan: ToolPlan): String? = pending[plan.fingerprint]?.let { snapshot ->
            JSONObject()
                .put("version", 1)
                .put("destinationName", snapshot.destinationName)
                .put("existingId", snapshot.existing?.id ?: JSONObject.NULL)
                .put("previousSha256", snapshot.existingSha256 ?: JSONObject.NULL)
                .put("nextSha256", snapshot.nextSha256)
                .toString()
        }

        override suspend fun reconcile(record: MutationRecord): ToolResult = withContext(Dispatchers.IO) {
            try {
                if (record.toolName != EXPORT_TOOL) throw IllegalArgumentException()
                val value = JSONObject(record.recoveryPayload)
                if (value.optInt("version") != 1) throw IllegalArgumentException()
                observeHashes(
                    record.callId,
                    requireScope(record.scopeId),
                    value.getString("destinationName"),
                    value.optionalNullableString("existingId"),
                    value.optionalNullableString("previousSha256"),
                    value.getString("nextSha256"),
                )
            } catch (_: Exception) {
                ToolResult.Unknown(record.callId, "Export state could not be reconciled")
            }
        }

        private suspend fun executeExport(
            plan: ToolPlan,
            snapshot: ExportSnapshot,
            dispatched: AtomicBoolean,
        ): ToolResult {
            currentCoroutineContext().ensureActive()
            val scope = requireScope(plan.scopeId)
            val source = store.get(snapshot.sourcePath)
            if (source?.sha256 != snapshot.sourceSha256) {
                throw ToolRejectedException("Private workspace source changed after preview")
            }
            val root = root(scope)
            if (root.id != snapshot.rootId) throw ToolRejectedException("Export folder changed after preview")
            val matches = children(scope, root.id).filter { it.name == snapshot.destinationName }
            if (matches.size > 1) throw ToolRejectedException("Export destination became ambiguous")
            val current = matches.singleOrNull()
            if (snapshot.existing == null && current != null || snapshot.existing != null && current != snapshot.existing) {
                throw ToolRejectedException("Export destination changed after preview")
            }
            if (current != null && readText(scope, current).sha256 != snapshot.existingSha256) {
                throw ToolRejectedException("Export destination content changed after preview")
            }
            currentCoroutineContext().ensureActive()

            dispatched.set(true)
            val destinationUri = if (current == null) {
                DocumentsContract.createDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(scope.treeUri, root.id),
                    mimeType(snapshot.destinationName),
                    snapshot.destinationName,
                ) ?: throw IllegalStateException("Provider did not create the export")
            } else {
                DocumentsContract.buildDocumentUriUsingTree(scope.treeUri, current.id)
            }
            resolver.openOutputStream(destinationUri, "rwt")?.use { output ->
                output.write(source.content.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            } ?: throw FileNotFoundException("Export stream is unavailable")
            return observe(plan.call, snapshot)
        }

        private fun observe(call: ToolCall, snapshot: ExportSnapshot): ToolResult = try {
            observeHashes(
                call.id,
                requireScope(snapshot.scopeId),
                snapshot.destinationName,
                snapshot.existing?.id,
                snapshot.existingSha256,
                snapshot.nextSha256,
            )
        } catch (_: Exception) {
            ToolResult.Unknown(call.id, "Export was dispatched but its result could not be observed")
        }
    }

    private fun observeHashes(
        callId: io.github.ciurlaro.codexmobile.core.ToolCallId,
        scope: Scope,
        destinationName: String,
        existingId: String?,
        previousSha256: String?,
        nextSha256: String,
    ): ToolResult {
        val matches = children(scope, root(scope).id).filter { it.name == destinationName }
        val destination = matches.singleOrNull()
        if (destination == null) {
            return if (existingId == null) {
                ToolResult.Failed(callId, "export_unchanged", "Provider state proves no export was created")
            } else {
                ToolResult.Unknown(callId, "Export destination disappeared after dispatch")
            }
        }
        if (existingId != null && destination.id != existingId) {
            return ToolResult.Unknown(callId, "Export destination identity changed after dispatch")
        }
        val sha256 = readBytes(scope, destination).sha256()
        return when (sha256) {
            nextSha256 -> ToolResult.Success(
                callId,
                JSONObject().put("status", "exported").put("name", destinationName)
                    .put("sha256", sha256).toString(),
            )
            previousSha256 -> ToolResult.Failed(
                callId,
                "export_unchanged",
                "Provider state proves the destination remained unchanged",
            )
            else -> ToolResult.Unknown(callId, "Export destination has unexpected content after dispatch")
        }
    }

    private fun root(scope: Scope): Metadata {
        val id = DocumentsContract.getTreeDocumentId(scope.treeUri)
        return queryOne(DocumentsContract.buildDocumentUriUsingTree(scope.treeUri, id), id).also {
            if (!it.isDirectory) throw ToolRejectedException("Export folder is unavailable")
        }
    }

    private fun children(scope: Scope, parentId: String): List<Metadata> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(scope.treeUri, parentId)
        return resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (size >= MAX_ENTRIES) throw ToolRejectedException("Export folder has too many entries")
                    add(cursor.metadata())
                }
            }
        } ?: throw ToolRejectedException("Export provider returned no folder listing")
    }

    private fun queryOne(uri: Uri, expectedId: String): Metadata =
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw ToolRejectedException("Export folder is unavailable")
            cursor.metadata().also {
                if (it.id != expectedId || cursor.moveToNext()) {
                    throw ToolRejectedException("Export provider returned invalid metadata")
                }
            }
        } ?: throw ToolRejectedException("Export provider returned no metadata")

    private fun Cursor.metadata(): Metadata = Metadata(
        id = requiredString(Document.COLUMN_DOCUMENT_ID),
        name = requiredString(Document.COLUMN_DISPLAY_NAME),
        mimeType = requiredString(Document.COLUMN_MIME_TYPE),
        size = optionalLong(Document.COLUMN_SIZE),
        modified = optionalLong(Document.COLUMN_LAST_MODIFIED),
        flags = getColumnIndex(Document.COLUMN_FLAGS).takeIf { it >= 0 && !isNull(it) }?.let(::getInt)
            ?: throw ToolRejectedException("Export provider returned invalid metadata"),
    )

    private fun Cursor.requiredString(column: String): String =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_METADATA_CHARS }
            ?: throw ToolRejectedException("Export provider returned invalid metadata")

    private fun Cursor.optionalLong(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)?.takeIf { it >= 0 }

    private fun readText(scope: Scope, metadata: Metadata): ExistingText {
        val bytes = readBytes(scope, metadata)
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw ToolRejectedException("Existing export is not UTF-8 text and cannot be safely diffed")
        }
        if ('\u0000' in text || text.count { it.isISOControl() && it !in "\n\r\t" } > 16) {
            throw ToolRejectedException("Existing export is binary and cannot be safely diffed")
        }
        return ExistingText(text, bytes.sha256())
    }

    private fun readBytes(scope: Scope, metadata: Metadata): ByteArray {
        if (metadata.isDirectory || metadata.size?.let { it > MAX_FILE_BYTES } == true) {
            throw ToolRejectedException("Existing export exceeds the safe text limit")
        }
        val uri = DocumentsContract.buildDocumentUriUsingTree(scope.treeUri, metadata.id)
        return resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > MAX_FILE_BYTES) {
                    throw ToolRejectedException("Existing export exceeds the safe text limit")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw FileNotFoundException("Export document is unavailable")
    }

    private fun strictArguments(json: String): JSONObject {
        if (json.length > MAX_ARGUMENT_CHARS) throw ToolRejectedException("Export arguments are too large")
        val tokener = JSONTokener(json)
        val value = runCatching { tokener.nextValue() }.getOrNull() as? JSONObject
            ?: throw ToolRejectedException("Export arguments must be one JSON object")
        val keys = value.keys().asSequence().toSet()
        if (
            tokener.nextClean() != '\u0000' || !setOf(WORKSPACE_ID, DESTINATION_NAME).containsAll(keys) ||
            WORKSPACE_ID !in keys || keys.any { value.opt(it) !is String }
        ) {
            throw ToolRejectedException("Export arguments do not match the registered schema")
        }
        if (value.getString(WORKSPACE_ID).isBlank()) throw ToolRejectedException("Workspace ID is required")
        if (value.has(DESTINATION_NAME) && value.getString(DESTINATION_NAME).isBlank()) {
            throw ToolRejectedException("Export filename is invalid")
        }
        return value
    }

    private fun validateName(name: String) {
        if (
            name.isBlank() || name.length > MAX_NAME_CHARS || name == "." || name == ".." ||
            '/' in name || '\\' in name || '\u0000' in name
        ) {
            throw ToolRejectedException("Export filename is invalid")
        }
    }

    private fun fingerprint(snapshot: ExportSnapshot): String = MessageDigest.getInstance("SHA-256").run {
        listOf(
            snapshot.call.id.value,
            snapshot.call.argumentsJson,
            snapshot.scopeId.value,
            snapshot.sourcePath,
            snapshot.sourceSha256,
            snapshot.destinationName,
            snapshot.rootId,
            snapshot.existing?.id.orEmpty(),
            snapshot.existingSha256.orEmpty(),
        ).forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            update(bytes)
        }
        digest().joinToString("") { "%02x".format(it) }
    }

    private fun fullDiff(path: String, before: String?, after: String): String = buildString {
        append("--- ").append(if (before == null) "/dev/null" else path).append('\n')
        append("+++ ").append(path).append("\n@@ full file @@\n")
        before?.lineSequence()?.forEach { append('-').append(it).append('\n') }
        after.lineSequence().forEach { append('+').append(it).append('\n') }
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        else -> "text/plain"
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun JSONObject.optionalNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private data class Scope(val id: ResourceScopeId, val treeUri: Uri)

    private data class Metadata(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long?,
        val modified: Long?,
        val flags: Int,
    ) {
        val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
    }

    private data class ExistingText(val text: String, val sha256: String)

    private data class ExportSnapshot(
        val call: ToolCall,
        val scopeId: ResourceScopeId,
        val sourcePath: String,
        val sourceSha256: String,
        val destinationName: String,
        val rootId: String,
        val existing: Metadata?,
        val existingSha256: String?,
        val nextSha256: String,
    )

    private companion object {
        const val EXPORT_PREFERENCES = "workspace_export_scope"
        const val ID_KEY = "id"
        const val URI_KEY = "uri"
        const val EXPORT_TOOL = "export_workspace_file"
        const val WORKSPACE_ID = "workspaceDocumentId"
        const val DESTINATION_NAME = "destinationName"
        const val MAX_FILE_BYTES = WorkspaceAuthority.MAX_FILE_BYTES
        const val MAX_DIFF_BYTES = 600 * 1024
        const val MAX_ARGUMENT_CHARS = 16 * 1024
        const val MAX_NAME_CHARS = 255
        const val MAX_METADATA_CHARS = 4 * 1024
        const val MAX_ENTRIES = 2_048
        const val MAX_PENDING = 32
        const val EXPORT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"workspaceDocumentId\":{\"type\":\"string\"},\"destinationName\":{\"type\":\"string\"}},\"required\":[\"workspaceDocumentId\"],\"additionalProperties\":false}"
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
