package io.github.ciurlaro.codexmobile.platform.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.ciurlaro.codexmobile.core.DeviceTool
import io.github.ciurlaro.codexmobile.core.MutationJournal
import io.github.ciurlaro.codexmobile.core.MutationRecord
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolDefinition
import io.github.ciurlaro.codexmobile.core.ToolEffect
import io.github.ciurlaro.codexmobile.core.ToolPlan
import io.github.ciurlaro.codexmobile.core.ToolRejectedException
import io.github.ciurlaro.codexmobile.core.ToolResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class AndroidPlatform internal constructor(
    context: Context,
    private val runtimeOverride: File?,
) {
    constructor(context: Context) : this(context, null)

    private val appContext = context.applicationContext
    private val workspaceStore = WorkspaceStore(appContext)
    private val workspaceAuthority = WorkspaceAuthority(workspaceStore)
    private val readAuthority = SafReadAuthority(appContext)
    private val exportAuthority = WorkspaceExportAuthority(appContext, workspaceStore)
    private val journal by lazy { AndroidMutationJournal(appContext) }

    fun launchProcess(command: List<String>, environment: Map<String, String>): Process {
        require(command == listOf(CODEX_APP_SERVER)) { "Only the bundled Codex app-server may run" }

        val runtime = runtimeOverride ?: File(appContext.applicationInfo.nativeLibraryDir, RUNTIME_FILE)
        check(runtime.isFile && runtime.canExecute()) { "Bundled Codex runtime is missing or not executable" }

        val codexHome = File(appContext.noBackupFilesDir, "codex").requireDirectory()
        val home = File(appContext.filesDir, "home").requireDirectory()
        val certificateBundle = prepareCertificateBundle(codexHome)
        val logsDatabase = File(codexHome, LOGS_DATABASE_FILE)
        sanitizeExistingRuntimeLogs(logsDatabase)
        val proxy = LoopbackConnectProxy()
        var started: Process? = null
        return try {
            val process = ProcessBuilder(runtime.absolutePath)
                .directory(home)
                .redirectErrorStream(false)
                .apply {
                    environment().putAll(environment)
                    environment()["CODEX_HOME"] = codexHome.absolutePath
                    environment()["CODEX_SQLITE_HOME"] = codexHome.absolutePath
                    environment()["HOME"] = home.absolutePath
                    environment()["TMPDIR"] = appContext.cacheDir.absolutePath
                    environment()["SSL_CERT_FILE"] = certificateBundle.absolutePath
                    environment()["HTTPS_PROXY"] = proxy.url
                    environment()["https_proxy"] = proxy.url
                    environment()["NO_COLOR"] = "1"
                }
                .start()
            started = process
            awaitRuntimeLogPrivacyGuard(logsDatabase, process)
            ProxyBackedProcess(process, proxy)
        } catch (error: Exception) {
            started?.destroyForcibly()
            proxy.close()
            throw error
        }
    }

    suspend fun persistScope(treeUri: Uri): ResourceScopeId = withContext(Dispatchers.IO) {
        readAuthority.persist(treeUri, exportAuthority.currentTreeUri())
    }

    suspend fun persistMutationScope(treeUri: Uri): ResourceScopeId = withContext(Dispatchers.IO) {
        readAuthority.persistMutation(treeUri, exportAuthority.currentTreeUri())
    }

    suspend fun persistExportScope(treeUri: Uri): ResourceScopeId = withContext(Dispatchers.IO) {
        exportAuthority.persist(
            treeUri,
            readAuthority.currentTreeUri(),
            readAuthority.currentScopeAllowsMutations(),
        )
    }

    suspend fun revokeScope(scopeId: ResourceScopeId) = withContext(Dispatchers.IO) {
        readAuthority.revoke(scopeId, exportAuthority.currentTreeUri())
    }

    suspend fun revokeExportScope(scopeId: ResourceScopeId) = withContext(Dispatchers.IO) {
        exportAuthority.revoke(
            scopeId,
            readAuthority.currentTreeUri(),
            readAuthority.currentScopeAllowsMutations(),
        )
    }

    fun currentScopeId(): ResourceScopeId? = readAuthority.currentScopeId()

    fun currentScopeAllowsMutations(): Boolean = readAuthority.currentScopeAllowsMutations()

    fun currentExportScopeId(): ResourceScopeId? = exportAuthority.currentScopeId()

    fun scopeIdForTool(toolName: String): ResourceScopeId? =
        when {
            workspaceAuthority.handles(toolName) -> workspaceAuthority.scopeId
            exportAuthority.handles(toolName) -> exportAuthority.currentScopeId()
            else -> readAuthority.currentScopeId()
        }

    fun deviceTools(): List<DeviceTool> = readAuthority.tools + workspaceAuthority.tools + exportAuthority.tools

    fun mutationJournal(): MutationJournal = journal

    private fun File.requireDirectory(): File = apply {
        check(isDirectory || mkdirs()) { "Unable to prepare private runtime directory" }
    }

    private fun prepareCertificateBundle(codexHome: File): File = synchronized(CERTIFICATE_LOCK) {
        val certificates = File(SYSTEM_CERTIFICATE_DIRECTORY)
            .listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        check(certificates.isNotEmpty()) { "Android system certificates are unavailable" }

        File(codexHome, "android-system-ca.pem").apply {
            outputStream().buffered().use { output ->
                certificates.forEach { certificate ->
                    certificate.inputStream().use { it.copyTo(output) }
                    output.write('\n'.code)
                }
            }
            check(length() > 0) { "Unable to prepare Android system certificates" }
        }
    }

    private fun sanitizeExistingRuntimeLogs(databaseFile: File) {
        if (!databaseFile.isFile) return
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            installRuntimeLogPrivacyGuard(database)
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            database.execSQL("VACUUM")
        }
    }

    private fun awaitRuntimeLogPrivacyGuard(databaseFile: File, process: Process) {
        val deadline = SystemClock.elapsedRealtime() + LOG_DATABASE_TIMEOUT_MILLIS
        var lastFailure: SQLiteException? = null
        while (process.isAlive && SystemClock.elapsedRealtime() < deadline) {
            if (databaseFile.isFile) {
                try {
                    SQLiteDatabase.openDatabase(
                        databaseFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    ).use(::installRuntimeLogPrivacyGuard)
                    return
                } catch (error: SQLiteException) {
                    lastFailure = error
                }
            }
            SystemClock.sleep(LOG_DATABASE_RETRY_MILLIS)
        }
        throw IllegalStateException("Unable to prepare the private Codex log store", lastFailure)
    }

    private companion object {
        const val CODEX_APP_SERVER = "codex-app-server"
        const val RUNTIME_FILE = "libcodex_app_server.so"
        const val LOGS_DATABASE_FILE = "logs_2.sqlite"
        const val LOG_DATABASE_TIMEOUT_MILLIS = 20_000L
        const val LOG_DATABASE_RETRY_MILLIS = 25L
        const val SYSTEM_CERTIFICATE_DIRECTORY = "/system/etc/security/cacerts"
        val CERTIFICATE_LOCK = Any()
    }
}

private class SafReadAuthority(context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences(SCOPE_PREFERENCES, Context.MODE_PRIVATE)
    private val documentReader = DocumentReader(context)
    private val lock = Any()

    val tools: List<DeviceTool> = listOf(ListDocumentsTool(), ReadDocumentTool(), RenameDocumentTool())

    fun persist(treeUri: Uri, protectedUri: Uri? = null): ResourceScopeId =
        persist(treeUri, allowMutations = false, protectedUri = protectedUri)

    fun persistMutation(treeUri: Uri, protectedUri: Uri? = null): ResourceScopeId =
        persist(treeUri, allowMutations = true, protectedUri = protectedUri)

    private fun persist(treeUri: Uri, allowMutations: Boolean, protectedUri: Uri?): ResourceScopeId {
        require(
            treeUri.scheme == ContentResolver.SCHEME_CONTENT &&
                treeUri.authority?.isNotBlank() == true &&
                DocumentsContract.isTreeUri(treeUri),
        ) { "Select a document-provider folder" }
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: throw ToolRejectedException("Selected folder is invalid")
        val root = querySingleDocument(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId),
            rootId,
        )
        if (!root.isDirectory) throw ToolRejectedException("Select a folder, not a document")

        synchronized(lock) {
            val previous = scopeOrNullLocked()
            val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                if (allowMutations) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
            resolver.takePersistableUriPermission(treeUri, grantFlags)
            if (!hasReadGrant(treeUri) || allowMutations && !hasWriteGrant(treeUri)) {
                throw SecurityException("The selected provider did not persist required access")
            }

            val id = ResourceScopeId(UUID.randomUUID().toString())
            val secret = ByteArray(SCOPE_SECRET_BYTES).also(SECURE_RANDOM::nextBytes)
            val saved = preferences.edit()
                .putString(SCOPE_ID_KEY, id.value)
                .putString(SCOPE_URI_KEY, treeUri.toString())
                .putString(SCOPE_SECRET_KEY, Base64.getEncoder().encodeToString(secret))
                .putBoolean(SCOPE_MUTATION_KEY, allowMutations)
                .commit()
            if (!saved) {
                if (previous?.treeUri != treeUri) releasePersistedGrant(treeUri)
                else if (previous.allowsMutations != true) releasePersistedWriteGrant(treeUri)
                error("Unable to save document scope")
            }
            if (!allowMutations && treeUri != protectedUri) releasePersistedWriteGrant(treeUri)
            if (previous != null && previous.treeUri != treeUri && previous.treeUri != protectedUri) {
                releasePersistedGrant(previous.treeUri)
            }
            return id
        }
    }

    fun revoke(scopeId: ResourceScopeId, protectedUri: Uri? = null) = synchronized(lock) {
        val scope = scopeOrNullLocked()
            ?: throw ToolRejectedException("Document scope is unavailable")
        if (scope.id != scopeId) throw ToolRejectedException("Document scope does not match")
        preferences.edit().clear().commit()
        if (scope.treeUri != protectedUri) releasePersistedGrant(scope.treeUri)
    }

    fun currentScopeId(): ResourceScopeId? = synchronized(lock) { scopeOrNullLocked()?.id }

    fun currentTreeUri(): Uri? = synchronized(lock) { scopeOrNullLocked()?.treeUri }

    fun currentScopeAllowsMutations(): Boolean = synchronized(lock) {
        scopeOrNullLocked()?.let { it.allowsMutations && hasWriteGrant(it.treeUri) } == true
    }

    private fun scopeOrNullLocked(): Scope? {
        val id = preferences.getString(SCOPE_ID_KEY, null)?.takeIf(String::isNotBlank)
        val uri = preferences.getString(SCOPE_URI_KEY, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val secret = preferences.getString(SCOPE_SECRET_KEY, null)
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (id == null || uri == null || secret?.size != SCOPE_SECRET_BYTES || !hasReadGrant(uri)) {
            if (preferences.all.isNotEmpty()) preferences.edit().clear().commit()
            return null
        }
        return Scope(
            id = ResourceScopeId(id),
            treeUri = uri,
            secret = secret,
            allowsMutations = preferences.getBoolean(SCOPE_MUTATION_KEY, false),
        )
    }

    private fun requireScope(scopeId: ResourceScopeId): Scope = synchronized(lock) {
        scopeOrNullLocked()?.takeIf { it.id == scopeId }
            ?: throw ToolRejectedException("Document scope is unavailable or does not match")
    }

    private fun requireMutationScope(scopeId: ResourceScopeId): Scope = synchronized(lock) {
        scopeOrNullLocked()?.takeIf {
            it.id == scopeId && it.allowsMutations && hasWriteGrant(it.treeUri)
        } ?: throw ToolRejectedException("Writable disposable document scope is unavailable")
    }

    private fun hasReadGrant(uri: Uri): Boolean = resolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }

    private fun hasWriteGrant(uri: Uri): Boolean = resolver.persistedUriPermissions.any {
        it.uri == uri && it.isWritePermission
    }

    private fun releasePersistedWriteGrant(uri: Uri) {
        if (resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) {
            runCatching {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    private fun releasePersistedGrant(uri: Uri) {
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags != 0) runCatching { resolver.releasePersistableUriPermission(uri, flags) }
    }

    private inner class ListDocumentsTool : DeviceTool {
        override val definition = ToolDefinition(
            name = LIST_TOOL_NAME,
            description = "List entries in the selected Android document folder or an opaque child folder ID.",
            inputSchemaJson = LIST_SCHEMA,
        )
        override val effect = ToolEffect.READ

        override suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan {
            val scope = requireScope(scopeId)
            val arguments = strictArguments(call.argumentsJson, setOf(DIRECTORY_ID_ARGUMENT))
            arguments.optionalString(DIRECTORY_ID_ARGUMENT)?.let { decodePath(it, scope) }
            return readPlan(call, scopeId, "List selected documents")
        }

        override suspend fun execute(plan: ToolPlan): ToolResult = withContext(Dispatchers.IO) {
            observedResult(plan) { scope ->
                val arguments = strictArguments(plan.call.argumentsJson, setOf(DIRECTORY_ID_ARGUMENT))
                val path = arguments.optionalString(DIRECTORY_ID_ARGUMENT)
                    ?.let { decodePath(it, scope) }
                    .orEmpty()
                val directory = resolvePath(scope, path)
                if (!directory.isDirectory) throw ToolRejectedException("Document ID is not a folder")
                val children = queryChildren(scope.treeUri, directory.id)
                    .sortedWith(compareBy<DocumentMetadata> { it.name }.thenBy { it.id })
                val entries = JSONArray()
                var estimatedBytes = 0
                children.forEach { child ->
                    if (child.id == directory.id || child.id in path) {
                        throw SafFailure("provider_cycle", "Document provider returned a cycle")
                    }
                    val token = encodePath(path + child.id, scope)
                    val entry = JSONObject()
                        .put("id", token)
                        .put("name", child.name)
                        .put("type", if (child.isDirectory) "directory" else "document")
                        .apply { child.size?.let { put("sizeBytes", it) } }
                    estimatedBytes += entry.toString().toByteArray(StandardCharsets.UTF_8).size + 1
                    if (estimatedBytes > MAX_LIST_OUTPUT_BYTES) {
                        throw SafFailure("directory_too_large", "Directory metadata exceeds the output limit")
                    }
                    entries.put(entry)
                }
                JSONObject().put("entries", entries).put("count", children.size).toString()
            }
        }
    }

    private inner class ReadDocumentTool : DeviceTool {
        override val definition = ToolDefinition(
            name = READ_TOOL_NAME,
            description = "Read one bounded document segment by opaque ID. Supports text, PDF, images, DOCX, PPTX, XLSX, and CSV; pass nextCursor until absent.",
            inputSchemaJson = READ_SCHEMA,
        )
        override val effect = ToolEffect.READ

        override suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan {
            val scope = requireScope(scopeId)
            val arguments = strictArguments(call.argumentsJson, setOf(DOCUMENT_ID_ARGUMENT, CURSOR_ARGUMENT))
            val path = decodePath(arguments.requiredString(DOCUMENT_ID_ARGUMENT), scope)
            if (path.isEmpty()) throw ToolRejectedException("A document ID is required")
            arguments.optionalString(CURSOR_ARGUMENT)?.let {
                decodeCursor(it, arguments.requiredString(DOCUMENT_ID_ARGUMENT), scope)
            }
            return readPlan(call, scopeId, "Read selected document")
        }

        override suspend fun execute(plan: ToolPlan): ToolResult = withContext(Dispatchers.IO) {
            try {
                val scope = requireScope(plan.scopeId)
                val arguments = strictArguments(
                    plan.call.argumentsJson,
                    setOf(DOCUMENT_ID_ARGUMENT, CURSOR_ARGUMENT),
                )
                val documentId = arguments.requiredString(DOCUMENT_ID_ARGUMENT)
                val path = decodePath(documentId, scope)
                if (path.isEmpty()) throw ToolRejectedException("A document ID is required")
                val resolved = resolvePath(scope, path)
                if (resolved.isDirectory) throw ToolRejectedException("Document ID refers to a folder")
                val uri = DocumentsContract.buildDocumentUriUsingTree(scope.treeUri, resolved.id)
                val current = querySingleDocument(uri, resolved.id)
                if (current != resolved) {
                    throw SafFailure("document_changed", "Document changed before it could be opened")
                }
                val cursor = arguments.optionalString(CURSOR_ARGUMENT)?.let {
                    decodeCursor(it, documentId, scope)
                }
                val read = documentReader.read(uri, resolved.size, cursor?.position ?: 0)
                if (cursor != null && cursor.sha256 != read.sha256) {
                    throw SafFailure("document_changed", "Document changed since the previous segment")
                }
                val output = JSONObject()
                    .put("name", resolved.name)
                    .put("format", read.format)
                    .put("sha256", read.sha256)
                    .put("position", read.position)
                    .put("text", read.text)
                    .put("byteCount", read.text.toByteArray(StandardCharsets.UTF_8).size)
                    .put("warnings", JSONArray(read.warnings))
                read.nextPosition?.let {
                    output.put("nextCursor", encodeCursor(documentId, read.sha256, it, scope))
                }
                ToolResult.Success(plan.call.id, output.toString(), read.imageUrls)
            } catch (error: ToolRejectedException) {
                ToolResult.Rejected(plan.call.id, error.message ?: "Document request was rejected")
            } catch (error: DocumentReadFailure) {
                ToolResult.Failed(plan.call.id, error.code, error.message ?: "Document read failed")
            } catch (error: SafFailure) {
                ToolResult.Failed(plan.call.id, error.code, error.publicMessage)
            } catch (_: SecurityException) {
                ToolResult.Failed(plan.call.id, "permission_denied", "Document permission is unavailable")
            } catch (_: FileNotFoundException) {
                ToolResult.Failed(plan.call.id, "not_found", "Document is unavailable")
            } catch (_: IOException) {
                ToolResult.Failed(plan.call.id, "io_failure", "Document stream failed")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ToolResult.Failed(plan.call.id, "provider_failure", "Document provider failed")
            }
        }
    }

    private inner class RenameDocumentTool : DeviceTool {
        private val pending = ConcurrentHashMap<String, ResolvedRename>()
        // ponytail: one global mutation lane; split by scope only if measured throughput needs it.
        private val mutationMutex = Mutex()

        override val definition = ToolDefinition(
            name = RENAME_TOOL_NAME,
            description = "Preview and rename one document inside the selected disposable Android folder.",
            inputSchemaJson = RENAME_SCHEMA,
        )
        override val effect = ToolEffect.MUTATION

        override suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan =
            withContext(Dispatchers.IO) {
                val scope = requireMutationScope(scopeId)
                val arguments = strictArguments(
                    call.argumentsJson,
                    setOf(DOCUMENT_ID_ARGUMENT, NEW_NAME_ARGUMENT),
                    requireAll = true,
                )
                val path = decodePath(arguments.requiredString(DOCUMENT_ID_ARGUMENT), scope)
                if (path.isEmpty()) throw ToolRejectedException("A document ID is required")
                val newName = arguments.requiredString(NEW_NAME_ARGUMENT)
                if (newName.length > MAX_DISPLAY_NAME_CHARS) {
                    throw ToolRejectedException("Destination name is too long")
                }

                val source = resolvePath(scope, path)
                if (source.isDirectory) throw ToolRejectedException("Only a disposable document can be renamed")
                if (source.flags and Document.FLAG_SUPPORTS_RENAME == 0) {
                    throw ToolRejectedException("Document provider does not support rename")
                }
                if (source.name == newName) throw ToolRejectedException("Document already has that name")

                val parentPath = path.dropLast(1)
                val parent = resolvePath(scope, parentPath)
                if (!parent.isDirectory) throw ToolRejectedException("Document parent is unavailable")
                if (queryChildren(scope.treeUri, parent.id).any {
                        it.id != source.id && it.name == newName
                    }
                ) {
                    throw ToolRejectedException("Destination name already exists")
                }

                val fingerprint = mutationFingerprint(call, scopeId, path, parent, source, newName)
                val snapshot = ResolvedRename(call, scopeId, path, parentPath, parent, source, newName)
                synchronized(pending) {
                    if (pending.size >= MAX_PENDING_RENAMES || pending.putIfAbsent(fingerprint, snapshot) != null) {
                        throw ToolRejectedException("A matching rename preview is already pending")
                    }
                }
                ToolPlan(
                    call = call,
                    scopeId = scopeId,
                    effect = effect,
                    summary = "Rename one disposable document",
                    fingerprint = fingerprint,
                    approvalPreview = io.github.ciurlaro.codexmobile.core.ApprovalPreview(
                        operation = "Rename document",
                        source = source.name,
                        destination = "${parent.name} / $newName",
                        scope = "Selected disposable folder",
                        conflictBehavior = CONFLICT_BEHAVIOR,
                    ),
                )
            }

        override suspend fun execute(plan: ToolPlan): ToolResult {
            val snapshot = pending.remove(plan.fingerprint)
                ?: return ToolResult.Rejected(plan.call.id, "Rename preview is unavailable or already used")
            if (
                snapshot.call != plan.call || snapshot.scopeId != plan.scopeId ||
                plan.effect != effect || plan.approvalPreview == null
            ) {
                return ToolResult.Rejected(plan.call.id, "Rename plan does not match its preview")
            }

            val dispatched = AtomicBoolean()
            return try {
                withContext(Dispatchers.IO) {
                    mutationMutex.withLock {
                        executeRename(plan, snapshot, dispatched)
                    }
                }
            } catch (error: CancellationException) {
                if (!dispatched.get()) throw error
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        mutationMutex.withLock { observeRename(plan, snapshot, null, error) }
                    }
                }
            } catch (error: ToolRejectedException) {
                ToolResult.Rejected(plan.call.id, error.message ?: "Rename request became stale")
            } catch (_: SecurityException) {
                ToolResult.Failed(plan.call.id, "permission_denied", "Writable document permission is unavailable")
            } catch (_: FileNotFoundException) {
                ToolResult.Failed(plan.call.id, "not_found", "Disposable document is unavailable")
            } catch (_: Exception) {
                ToolResult.Failed(plan.call.id, "provider_failure", "Document provider failed before rename")
            }
        }

        override fun abandon(plan: ToolPlan) {
            pending.remove(plan.fingerprint)?.takeIf {
                it.call == plan.call && it.scopeId == plan.scopeId
            } ?: return
        }

        override fun recoveryPayload(plan: ToolPlan): String? {
            val snapshot = pending[plan.fingerprint]?.takeIf {
                it.call == plan.call && it.scopeId == plan.scopeId
            } ?: return null
            val parentPath = JSONArray()
            snapshot.parentPath.forEach(parentPath::put)
            return JSONObject()
                .put("version", RENAME_RECOVERY_VERSION)
                .put("parentPath", parentPath)
                .put("parentId", snapshot.parent.id)
                .put("sourceId", snapshot.source.id)
                .put("sourceName", snapshot.source.name)
                .put("destinationName", snapshot.newName)
                .toString()
        }

        override suspend fun reconcile(record: MutationRecord): ToolResult =
            withContext(Dispatchers.IO) {
                try {
                    if (record.toolName != name) {
                        throw ToolRejectedException("Mutation tool does not match recovery data")
                    }
                    val recovery = decodeRenameRecovery(record.recoveryPayload)
                    val scope = requireScope(record.scopeId)
                    val parent = resolvePath(scope, recovery.parentPath)
                    if (parent.id != recovery.parentId) {
                        throw ToolRejectedException("Mutation parent changed before recovery")
                    }
                    val children = queryChildren(scope.treeUri, parent.id)
                    val oldObserved = children.any {
                        it.id == recovery.sourceId && it.name == recovery.sourceName
                    }
                    val destinations = children.filter { it.name == recovery.destinationName }
                    when {
                        !oldObserved && destinations.size == 1 -> ToolResult.Success(
                            record.callId,
                            JSONObject().put("status", "reconciled_renamed").toString(),
                        )

                        oldObserved && destinations.isEmpty() -> ToolResult.Failed(
                            record.callId,
                            "reconciled_unchanged",
                            "Provider state proves the source remained unchanged",
                        )

                        else -> ToolResult.Unknown(
                            record.callId,
                            "Provider state remains ambiguous after rename interruption",
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    ToolResult.Unknown(
                        record.callId,
                        "Android mutation state could not be re-observed",
                    )
                }
            }

        private suspend fun executeRename(
            plan: ToolPlan,
            snapshot: ResolvedRename,
            dispatched: AtomicBoolean,
        ): ToolResult {
            currentCoroutineContext().ensureActive()
            val scope = requireMutationScope(plan.scopeId)
            val source = resolvePath(scope, snapshot.path)
            val parent = resolvePath(scope, snapshot.parentPath)
            if (source != snapshot.source || parent != snapshot.parent) {
                throw ToolRejectedException("Rename preview is stale")
            }
            if (queryChildren(scope.treeUri, parent.id).any {
                    it.id != source.id && it.name == snapshot.newName
                }
            ) {
                throw ToolRejectedException("Destination name now exists")
            }
            currentCoroutineContext().ensureActive()

            val sourceUri = DocumentsContract.buildDocumentUriUsingTree(scope.treeUri, source.id)
            var returnedUri: Uri? = null
            var providerFailure: Exception? = null
            dispatched.set(true)
            try {
                returnedUri = DocumentsContract.renameDocument(resolver, sourceUri, snapshot.newName)
            } catch (error: Exception) {
                providerFailure = error
            }
            return observeRename(plan, snapshot, returnedUri, providerFailure)
        }

        private fun observeRename(
            plan: ToolPlan,
            snapshot: ResolvedRename,
            returnedUri: Uri?,
            providerFailure: Exception?,
        ): ToolResult {
            return try {
                val scope = requireMutationScope(plan.scopeId)
                val children = queryChildren(scope.treeUri, snapshot.parent.id)
                val oldObserved = children.any {
                    it.id == snapshot.source.id && it.name == snapshot.source.name
                }
                val destinations = children.filter { it.name == snapshot.newName }
                val destination = destinations.singleOrNull()

                when {
                    destination != null && !oldObserved -> ToolResult.Success(
                        plan.call.id,
                        JSONObject()
                            .put("status", "renamed")
                            .put("documentId", encodePath(snapshot.parentPath + destination.id, scope))
                            .put("name", destination.name)
                            .put("providerReturnedDocument", returnedUri != null)
                            .toString(),
                    )

                    oldObserved && destinations.isEmpty() -> ToolResult.Failed(
                        plan.call.id,
                        if (providerFailure == null) "rename_refused" else "rename_failed",
                        "Provider state proves the source remained unchanged",
                    )

                    else -> ToolResult.Unknown(
                        plan.call.id,
                        "Rename was dispatched; observed provider state is partial or unexpected",
                    )
                }
            } catch (_: Exception) {
                ToolResult.Unknown(
                    plan.call.id,
                    "Rename was dispatched; provider state could not be re-observed",
                )
            }
        }

        private fun decodeRenameRecovery(payload: String): RenameRecovery {
            if (payload.toByteArray(StandardCharsets.UTF_8).size > MAX_RECOVERY_PAYLOAD_BYTES) {
                throw ToolRejectedException("Mutation recovery data is too large")
            }
            val tokener = JSONTokener(payload)
            val value = runCatching { tokener.nextValue() }.getOrNull() as? JSONObject
                ?: throw ToolRejectedException("Mutation recovery data is invalid")
            if (tokener.nextClean() != '\u0000' || value.keys().asSequence().toSet() != RECOVERY_KEYS) {
                throw ToolRejectedException("Mutation recovery data is invalid")
            }
            if (value.optInt("version", -1) != RENAME_RECOVERY_VERSION) {
                throw ToolRejectedException("Mutation recovery version is unsupported")
            }
            val parentPathValue = value.optJSONArray("parentPath")
                ?: throw ToolRejectedException("Mutation recovery path is invalid")
            if (parentPathValue.length() > MAX_DOCUMENT_DEPTH) {
                throw ToolRejectedException("Mutation recovery path is invalid")
            }
            val parentPath = buildList {
                repeat(parentPathValue.length()) { index ->
                    val id = parentPathValue.opt(index) as? String
                        ?: throw ToolRejectedException("Mutation recovery path is invalid")
                    if (id.isEmpty() || id.toByteArray(StandardCharsets.UTF_8).size > MAX_DOCUMENT_ID_BYTES) {
                        throw ToolRejectedException("Mutation recovery path is invalid")
                    }
                    add(id)
                }
            }
            fun required(name: String, maxLength: Int): String {
                val text = value.opt(name) as? String
                    ?: throw ToolRejectedException("Mutation recovery data is invalid")
                if (text.isEmpty() || text.length > maxLength) {
                    throw ToolRejectedException("Mutation recovery data is invalid")
                }
                return text
            }
            return RenameRecovery(
                parentPath = parentPath,
                parentId = required("parentId", MAX_DOCUMENT_ID_BYTES),
                sourceId = required("sourceId", MAX_DOCUMENT_ID_BYTES),
                sourceName = required("sourceName", MAX_DISPLAY_NAME_CHARS),
                destinationName = required("destinationName", MAX_DISPLAY_NAME_CHARS),
            )
        }
    }

    private inline fun observedResult(plan: ToolPlan, operation: (Scope) -> String): ToolResult {
        return try {
            ToolResult.Success(plan.call.id, operation(requireScope(plan.scopeId)))
        } catch (error: ToolRejectedException) {
            ToolResult.Rejected(plan.call.id, error.message ?: "Document request was rejected")
        } catch (error: SafFailure) {
            ToolResult.Failed(plan.call.id, error.code, error.publicMessage)
        } catch (_: SecurityException) {
            ToolResult.Failed(plan.call.id, "permission_denied", "Document permission is unavailable")
        } catch (_: FileNotFoundException) {
            ToolResult.Failed(plan.call.id, "not_found", "Document is unavailable")
        } catch (_: IOException) {
            ToolResult.Failed(plan.call.id, "io_failure", "Document stream failed")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ToolResult.Failed(plan.call.id, "provider_failure", "Document provider failed")
        }
    }

    private fun readPlan(call: ToolCall, scopeId: ResourceScopeId, summary: String): ToolPlan {
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("${scopeId.value}\u0000${call.name}\u0000${call.argumentsJson}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return ToolPlan(call, scopeId, ToolEffect.READ, summary, fingerprint)
    }

    private fun mutationFingerprint(
        call: ToolCall,
        scopeId: ResourceScopeId,
        path: List<String>,
        parent: DocumentMetadata,
        source: DocumentMetadata,
        newName: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val values = listOf(
            call.id.value,
            call.name,
            call.argumentsJson,
            scopeId.value,
            *path.toTypedArray(),
            parent.id,
            parent.name,
            parent.mimeType,
            parent.size?.toString().orEmpty(),
            parent.lastModified?.toString().orEmpty(),
            parent.flags.toString(),
            source.id,
            source.name,
            source.mimeType,
            source.size?.toString().orEmpty(),
            source.lastModified?.toString().orEmpty(),
            source.flags.toString(),
            newName,
        )
        values.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun strictArguments(
        json: String,
        allowed: Set<String>,
        requireAll: Boolean = false,
    ): JSONObject {
        if (json.length > MAX_TOOL_ARGUMENT_CHARS) {
            throw ToolRejectedException("Tool arguments are too large")
        }
        val tokener = JSONTokener(json)
        val value = runCatching { tokener.nextValue() }.getOrNull()
        if (value !is JSONObject || tokener.nextClean() != '\u0000') {
            throw ToolRejectedException("Tool arguments must be one JSON object")
        }
        val keys = value.keys().asSequence().toSet()
        if (!allowed.containsAll(keys) || requireAll && keys != allowed) {
            throw ToolRejectedException("Tool arguments do not match the registered schema")
        }
        keys.forEach { key ->
            if (value.opt(key) !is String || (value.opt(key) as String).isBlank()) {
                throw ToolRejectedException("Tool argument $key must be a non-empty string")
            }
        }
        return value
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name)) opt(name) as? String else null

    private fun JSONObject.requiredString(name: String): String =
        optionalString(name) ?: throw ToolRejectedException("Tool argument $name is required")

    private fun resolvePath(scope: Scope, path: List<String>): DocumentMetadata {
        val rootId = DocumentsContract.getTreeDocumentId(scope.treeUri)
        var current = querySingleDocument(
            DocumentsContract.buildDocumentUriUsingTree(scope.treeUri, rootId),
            rootId,
        )
        path.forEach { childId ->
            if (!current.isDirectory) throw ToolRejectedException("Document path crosses a non-folder")
            current = queryChildren(scope.treeUri, current.id)
                .singleOrNull { it.id == childId }
                ?: throw ToolRejectedException("Document ID is outside the selected folder or unavailable")
        }
        return current
    }

    private fun querySingleDocument(uri: Uri, expectedId: String): DocumentMetadata {
        val cursor = resolver.query(uri, DOCUMENT_PROJECTION, null, null, null)
            ?: throw SafFailure("provider_null", "Document provider returned no metadata")
        cursor.use {
            if (!it.moveToFirst()) throw SafFailure("not_found", "Document is unavailable")
            val metadata = it.documentMetadata()
            if (metadata.id != expectedId) {
                throw SafFailure("provider_redirect", "Document provider redirected the requested ID")
            }
            if (it.moveToNext()) throw SafFailure("provider_duplicate", "Document provider returned duplicates")
            return metadata
        }
    }

    private fun queryChildren(treeUri: Uri, parentId: String): List<DocumentMetadata> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = resolver.query(uri, DOCUMENT_PROJECTION, null, null, null)
            ?: throw SafFailure("provider_null", "Document provider returned no directory rows")
        return cursor.use {
            val rows = ArrayList<DocumentMetadata>()
            val ids = HashSet<String>()
            while (it.moveToNext()) {
                if (rows.size >= MAX_DIRECTORY_ENTRIES) {
                    throw SafFailure("directory_too_large", "Directory contains too many entries")
                }
                val metadata = it.documentMetadata()
                if (!ids.add(metadata.id)) {
                    throw SafFailure("provider_duplicate", "Document provider returned duplicate IDs")
                }
                if (!isDescendant(treeUri, metadata.id)) {
                    throw SafFailure("provider_escape", "Document provider returned an out-of-scope ID")
                }
                rows += metadata
            }
            rows
        }
    }

    private fun Cursor.documentMetadata(): DocumentMetadata {
        val id = requiredString(Document.COLUMN_DOCUMENT_ID, MAX_DOCUMENT_ID_BYTES)
        val name = requiredString(Document.COLUMN_DISPLAY_NAME, MAX_DISPLAY_NAME_CHARS)
        val mimeType = requiredString(Document.COLUMN_MIME_TYPE, MAX_MIME_TYPE_CHARS)
        val flagsIndex = getColumnIndex(Document.COLUMN_FLAGS)
        val flags = flagsIndex.takeIf { it >= 0 && !isNull(it) }?.let(::getInt)
            ?: throw SafFailure("provider_metadata", "Document provider returned invalid metadata")
        val sizeIndex = getColumnIndex(Document.COLUMN_SIZE)
        val modifiedIndex = getColumnIndex(Document.COLUMN_LAST_MODIFIED)
        val size = sizeIndex.takeIf { it >= 0 && !isNull(it) }?.let(::getLong)?.takeIf { it >= 0 }
        val modified = modifiedIndex.takeIf { it >= 0 && !isNull(it) }?.let(::getLong)?.takeIf { it >= 0 }
        return DocumentMetadata(id, name, mimeType, size, modified, flags)
    }

    private fun Cursor.requiredString(column: String, maxLength: Int): String {
        val index = getColumnIndex(column)
        val value = index.takeIf { it >= 0 && !isNull(it) }?.let(::getString)
        if (value.isNullOrEmpty() || value.length > maxLength) {
            throw SafFailure("provider_metadata", "Document provider returned invalid metadata")
        }
        return value
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var emptyReads = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                if (++emptyReads > MAX_EMPTY_READS) {
                    throw SafFailure("stream_stalled", "Document stream stopped making progress")
                }
                continue
            }
            emptyReads = 0
            if (output.size() + count > MAX_DOCUMENT_BYTES) {
                throw SafFailure("document_too_large", "Document exceeds the read limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isDescendant(treeUri: Uri, documentId: String): Boolean {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        if (documentId == rootId) return true
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                DocumentsContract.isChildDocument(resolver, rootUri, documentUri)
            } else {
                val extras = Bundle().apply {
                    putParcelable(COMPAT_EXTRA_URI, rootUri)
                    putParcelable(COMPAT_EXTRA_TARGET_URI, documentUri)
                }
                resolver.call(
                    rootUri,
                    COMPAT_METHOD_IS_CHILD_DOCUMENT,
                    null,
                    extras,
                )?.getBoolean(COMPAT_EXTRA_RESULT, false) == true
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun encodePath(path: List<String>, scope: Scope): String {
        require(path.size <= MAX_DOCUMENT_DEPTH)
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(path.size)
                path.forEach { id ->
                    val encoded = id.toByteArray(StandardCharsets.UTF_8)
                    require(encoded.isNotEmpty() && encoded.size <= MAX_DOCUMENT_ID_BYTES)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
        }.toByteArray()
        check(payload.size <= MAX_TOKEN_PAYLOAD_BYTES)
        val signature = hmac(payload, scope.secret)
        return BASE64_URL.encodeToString(payload) + "." + BASE64_URL.encodeToString(signature)
    }

    private fun decodePath(token: String, scope: Scope): List<String> {
        if (token.length > MAX_TOKEN_CHARS) throw ToolRejectedException("Document ID is invalid")
        val parts = token.split('.', limit = 3)
        if (parts.size != 2) throw ToolRejectedException("Document ID is invalid")
        val payload = runCatching { BASE64_URL_DECODER.decode(parts[0]) }.getOrNull()
            ?: throw ToolRejectedException("Document ID is invalid")
        val supplied = runCatching { BASE64_URL_DECODER.decode(parts[1]) }.getOrNull()
            ?: throw ToolRejectedException("Document ID is invalid")
        if (
            payload.size > MAX_TOKEN_PAYLOAD_BYTES || supplied.size != SCOPE_SECRET_BYTES ||
            !MessageDigest.isEqual(hmac(payload, scope.secret), supplied)
        ) {
            throw ToolRejectedException("Document ID does not belong to the current scope")
        }
        return runCatching {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val count = input.readInt()
                require(count in 0..MAX_DOCUMENT_DEPTH)
                buildList(count) {
                    repeat(count) {
                        val size = input.readInt()
                        require(size in 1..MAX_DOCUMENT_ID_BYTES)
                        val encoded = ByteArray(size)
                        input.readFully(encoded)
                        add(String(encoded, StandardCharsets.UTF_8))
                    }
                    require(input.read() == -1)
                }
            }
        }.getOrElse { throw ToolRejectedException("Document ID is invalid") }
    }

    private fun encodeCursor(documentId: String, sha256: String, position: Int, scope: Scope): String {
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF(documentId)
                output.writeUTF(sha256)
                output.writeInt(position)
            }
        }.toByteArray()
        val signature = hmac(payload, scope.secret)
        return BASE64_URL.encodeToString(payload) + "." + BASE64_URL.encodeToString(signature)
    }

    private fun decodeCursor(token: String, documentId: String, scope: Scope): ReadCursor {
        if (token.length > MAX_TOKEN_CHARS) throw ToolRejectedException("Document cursor is invalid")
        val parts = token.split('.', limit = 3)
        if (parts.size != 2) throw ToolRejectedException("Document cursor is invalid")
        val payload = runCatching { BASE64_URL_DECODER.decode(parts[0]) }.getOrNull()
            ?: throw ToolRejectedException("Document cursor is invalid")
        val supplied = runCatching { BASE64_URL_DECODER.decode(parts[1]) }.getOrNull()
            ?: throw ToolRejectedException("Document cursor is invalid")
        if (
            payload.size > MAX_TOKEN_PAYLOAD_BYTES || supplied.size != SCOPE_SECRET_BYTES ||
            !MessageDigest.isEqual(hmac(payload, scope.secret), supplied)
        ) {
            throw ToolRejectedException("Document cursor does not belong to the current scope")
        }
        return runCatching {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val encodedDocumentId = input.readUTF()
                val sha256 = input.readUTF()
                val position = input.readInt()
                require(input.read() == -1 && encodedDocumentId == documentId)
                require(sha256.matches(Regex("[0-9a-f]{64}")) && position >= 0)
                ReadCursor(sha256, position)
            }
        }.getOrElse { throw ToolRejectedException("Document cursor is invalid") }
    }

    private fun hmac(payload: ByteArray, secret: ByteArray): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(SecretKeySpec(secret, HMAC_ALGORITHM))
            doFinal(payload)
        }

    private data class Scope(
        val id: ResourceScopeId,
        val treeUri: Uri,
        val secret: ByteArray,
        val allowsMutations: Boolean,
    )

    private data class DocumentMetadata(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long?,
        val lastModified: Long?,
        val flags: Int,
    ) {
        val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
    }

    private data class ResolvedRename(
        val call: ToolCall,
        val scopeId: ResourceScopeId,
        val path: List<String>,
        val parentPath: List<String>,
        val parent: DocumentMetadata,
        val source: DocumentMetadata,
        val newName: String,
    )

    private data class RenameRecovery(
        val parentPath: List<String>,
        val parentId: String,
        val sourceId: String,
        val sourceName: String,
        val destinationName: String,
    )

    private data class ReadCursor(val sha256: String, val position: Int)

    private class SafFailure(val code: String, val publicMessage: String) : Exception(publicMessage)

    private companion object {
        const val SCOPE_PREFERENCES = "resource_scope"
        const val SCOPE_ID_KEY = "id"
        const val SCOPE_URI_KEY = "uri"
        const val SCOPE_SECRET_KEY = "secret"
        const val SCOPE_MUTATION_KEY = "mutations"
        const val SCOPE_SECRET_BYTES = 32
        const val LIST_TOOL_NAME = "list_documents"
        const val READ_TOOL_NAME = "read_document"
        const val RENAME_TOOL_NAME = "rename_document"
        const val DIRECTORY_ID_ARGUMENT = "directoryId"
        const val DOCUMENT_ID_ARGUMENT = "documentId"
        const val CURSOR_ARGUMENT = "cursor"
        const val NEW_NAME_ARGUMENT = "newName"
        const val MAX_DOCUMENT_BYTES = 64 * 1024
        const val MAX_DIRECTORY_ENTRIES = 2_048
        const val MAX_LIST_OUTPUT_BYTES = 512 * 1024
        const val MAX_DOCUMENT_DEPTH = 64
        const val MAX_DOCUMENT_ID_BYTES = 4 * 1024
        const val MAX_DISPLAY_NAME_CHARS = 4 * 1024
        const val MAX_MIME_TYPE_CHARS = 512
        const val MAX_TOKEN_PAYLOAD_BYTES = 32 * 1024
        const val MAX_TOKEN_CHARS = 64 * 1024
        const val MAX_TOOL_ARGUMENT_CHARS = 72 * 1024
        const val MAX_RECOVERY_PAYLOAD_BYTES = 64 * 1024
        const val MAX_EMPTY_READS = 16
        const val MAX_PENDING_RENAMES = 64
        const val RENAME_RECOVERY_VERSION = 1
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val CONFLICT_BEHAVIOR =
            "Reject if the destination name is already listed; the provider may reject additional names"
        const val COMPAT_METHOD_IS_CHILD_DOCUMENT = "android:isChildDocument"
        const val COMPAT_EXTRA_URI = "uri"
        const val COMPAT_EXTRA_TARGET_URI = "android.content.extra.TARGET_URI"
        const val COMPAT_EXTRA_RESULT = "result"
        val RECOVERY_KEYS = setOf(
            "version",
            "parentPath",
            "parentId",
            "sourceId",
            "sourceName",
            "destinationName",
        )
        const val LIST_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"directoryId\":{\"type\":\"string\"}},\"additionalProperties\":false}"
        const val READ_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"documentId\":{\"type\":\"string\"},\"cursor\":{\"type\":\"string\"}},\"required\":[\"documentId\"],\"additionalProperties\":false}"
        const val RENAME_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"documentId\":{\"type\":\"string\"},\"newName\":{\"type\":\"string\"}},\"required\":[\"documentId\",\"newName\"],\"additionalProperties\":false}"
        val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
        val SECURE_RANDOM = SecureRandom()
        val BASE64_URL = Base64.getUrlEncoder().withoutPadding()
        val BASE64_URL_DECODER = Base64.getUrlDecoder()
    }
}

internal fun installRuntimeLogPrivacyGuard(database: SQLiteDatabase) {
    database.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    database.beginTransaction()
    try {
        database.delete("logs", null, null)
        database.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS codex_mobile_drop_runtime_logs
            BEFORE INSERT ON logs
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent(),
        )
        database.setTransactionSuccessful()
    } finally {
        database.endTransaction()
    }
}

private class ProxyBackedProcess(
    private val process: Process,
    private val proxy: LoopbackConnectProxy,
) : Process() {
    private val proxyClosed = AtomicBoolean()

    override fun getOutputStream(): OutputStream = process.outputStream
    override fun getInputStream(): InputStream = process.inputStream
    override fun getErrorStream(): InputStream = process.errorStream
    override fun waitFor(): Int = try {
        process.waitFor()
    } finally {
        closeProxy()
    }
    override fun exitValue(): Int = process.exitValue().also { closeProxy() }
    override fun destroy() {
        closeProxy()
        process.destroy()
    }

    private fun closeProxy() {
        if (proxyClosed.compareAndSet(false, true)) proxy.close()
    }
}

internal class LoopbackConnectProxy : AutoCloseable {
    private val closed = AtomicBoolean()
    private val server = ServerSocket()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool()
    private val authorization: String
    val url: String

    init {
        val password = UUID.randomUUID().toString()
        authorization = "Basic " + Base64.getEncoder().encodeToString(
            "codex:$password".toByteArray(StandardCharsets.UTF_8),
        )
        server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 8)
        url = "http://codex:$password@$LOOPBACK:${server.localPort}"
        workers.execute(::acceptConnections)
    }

    private fun acceptConnections() {
        while (!closed.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            sockets += socket
            workers.execute { handle(socket) }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var tunnelEstablished = false
        try {
            client.soTimeout = CONNECT_TIMEOUT_MILLIS
            val lines = readHeaders(client.inputStream).split("\r\n")
            if (lines.firstOrNull()?.startsWith("CONNECT ") != true) {
                respond(client, 405, "Method Not Allowed")
                return
            }
            val suppliedAuthorization = lines.firstOrNull {
                it.startsWith("Proxy-Authorization:", ignoreCase = true)
            }?.substringAfter(':')?.trim()
            if (suppliedAuthorization != authorization) {
                respond(client, 407, "Proxy Authentication Required")
                return
            }

            val authority = lines.first().split(' ').getOrNull(1).orEmpty()
            val destination = runCatching { URI("https://$authority") }.getOrNull()
            val host = destination?.host
            if (host == null || destination.port != 443 || !host.isAllowedCodexHost()) {
                respond(client, 403, "Forbidden")
                return
            }

            upstream = Socket().apply {
                connect(InetSocketAddress(host, 443), CONNECT_TIMEOUT_MILLIS)
            }
            sockets += upstream
            client.soTimeout = 0
            respond(client, 200, "Connection Established")
            tunnelEstablished = true
            val reverse = workers.submit {
                try {
                    upstream.inputStream.copyTo(client.outputStream)
                    client.outputStream.flush()
                    runCatching { client.shutdownOutput() }
                } catch (error: Exception) {
                    closePair(client, upstream)
                    throw error
                }
            }
            try {
                client.inputStream.copyTo(upstream.outputStream)
                upstream.outputStream.flush()
                runCatching { upstream.shutdownOutput() }
                reverse.get()
            } finally {
                reverse.cancel(true)
            }
        } catch (_: Exception) {
            if (!tunnelEstablished) runCatching { respond(client, 502, "Bad Gateway") }
        } finally {
            closePair(client, upstream)
        }
    }

    private fun readHeaders(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (bytes.size() < MAX_HEADER_BYTES) {
            val byte = input.read()
            check(byte >= 0) { "Proxy request ended before its headers" }
            bytes.write(byte)
            matched = when {
                byte == HEADER_END[matched].toInt() -> matched + 1
                byte == HEADER_END[0].toInt() -> 1
                else -> 0
            }
            if (matched == HEADER_END.size) {
                return bytes.toString(StandardCharsets.ISO_8859_1.name())
            }
        }
        error("Proxy request headers exceed the byte limit")
    }

    private fun respond(socket: Socket, status: Int, reason: String) {
        socket.outputStream.write("HTTP/1.1 $status $reason\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        socket.outputStream.flush()
    }

    private fun closePair(first: Socket, second: Socket?) {
        sockets -= first
        second?.let { sockets -= it }
        runCatching { first.close() }
        runCatching { second?.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        sockets.toList().forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    private fun String.isAllowedCodexHost(): Boolean {
        val normalized = lowercase()
        return normalized == "openai.com" || normalized.endsWith(".openai.com") ||
            normalized == "chatgpt.com" || normalized.endsWith(".chatgpt.com")
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val MAX_HEADER_BYTES = 16 * 1024
        val HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    }
}
