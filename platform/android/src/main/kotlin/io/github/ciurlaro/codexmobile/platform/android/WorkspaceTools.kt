package io.github.ciurlaro.codexmobile.platform.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class WorkspaceFile(
    val path: String,
    val content: String,
    val sha256: String,
    val version: Long,
)

internal data class WorkspaceChange(
    val operation: String,
    val path: String,
    val content: String,
    val expectedSha256: String?,
)

internal data class WorkspaceFileSummary(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal class WorkspaceStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        WORKSPACE_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val secret: ByteArray by lazy {
        preferences.getString(SECRET_KEY, null)
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?.takeIf { it.size == SECRET_BYTES }
            ?: ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes).also {
                check(
                    preferences.edit().putString(SECRET_KEY, Base64.getEncoder().encodeToString(it)).commit(),
                ) { "Unable to save workspace identity" }
            }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE workspace_files (
                path TEXT PRIMARY KEY NOT NULL,
                content TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                version INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE workspace_commits (
                commit_id TEXT PRIMARY KEY NOT NULL,
                committed_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun list(): List<WorkspaceFileSummary> = readableDatabase.rawQuery(
        "SELECT path, length(CAST(content AS BLOB)), sha256 FROM workspace_files ORDER BY path",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(WorkspaceFileSummary(cursor.getString(0), cursor.getLong(1), cursor.getString(2)))
            }
        }
    }

    @Synchronized
    fun get(path: String): WorkspaceFile? = readableDatabase.rawQuery(
        "SELECT path, content, sha256, version FROM workspace_files WHERE path = ?",
        arrayOf(validatePath(path)),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null
        else WorkspaceFile(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))
    }

    fun getById(documentId: String): WorkspaceFile? = get(decodeId(documentId))

    @Synchronized
    fun apply(commitId: String, changes: List<WorkspaceChange>): List<WorkspaceFile> {
        require(changes.isNotEmpty() && changes.size <= MAX_CHANGES)
        val database = writableDatabase
        database.beginTransaction()
        try {
            var fileCount = database.rawQuery("SELECT COUNT(*) FROM workspace_files", null).use {
                check(it.moveToFirst())
                it.getInt(0)
            }
            changes.forEach { change ->
                val existing = query(database, change.path)
                when (change.operation) {
                    "create" -> {
                        if (existing != null) {
                            throw ToolRejectedException("Workspace file ${change.path} already exists")
                        }
                        if (++fileCount > MAX_FILES) {
                            throw ToolRejectedException("Private workspace has reached its file limit")
                        }
                    }

                    "replace" -> if (existing?.sha256 != change.expectedSha256) {
                        throw ToolRejectedException("Workspace file ${change.path} changed after preview")
                    }

                    else -> throw ToolRejectedException("Workspace operation is invalid")
                }
                val values = ContentValues().apply {
                    put("path", change.path)
                    put("content", change.content)
                    put("sha256", change.content.sha256())
                    put("version", (existing?.version ?: 0L) + 1L)
                    put("updated_at", System.currentTimeMillis())
                }
                database.insertWithOnConflict(
                    "workspace_files",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                ).also { check(it >= 0) { "Unable to store workspace file" } }
            }
            database.insertOrThrow(
                "workspace_commits",
                null,
                ContentValues().apply {
                    put("commit_id", commitId)
                    put("committed_at", System.currentTimeMillis())
                },
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return changes.map { checkNotNull(get(it.path)) }
    }

    @Synchronized
    fun hasCommit(commitId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM workspace_commits WHERE commit_id = ?",
        arrayOf(commitId),
    ).use { it.moveToFirst() }

    fun encodeId(path: String): String = signed(validatePath(path).toByteArray(StandardCharsets.UTF_8))

    fun decodeId(documentId: String): String {
        val payload = verified(documentId)
        return validatePath(String(payload, StandardCharsets.UTF_8))
    }

    fun encodeCursor(path: String, sha256: String, position: Int): String = signed(
        JSONObject().put("path", path).put("sha256", sha256).put("position", position)
            .toString().toByteArray(StandardCharsets.UTF_8),
    )

    fun decodeCursor(cursor: String, file: WorkspaceFile): Int {
        val value = JSONObject(String(verified(cursor), StandardCharsets.UTF_8))
        if (
            value.optString("path") != file.path || value.optString("sha256") != file.sha256 ||
            value.optInt("position", -1) !in 0..file.content.length
        ) {
            throw ToolRejectedException("Workspace cursor is stale or invalid")
        }
        return value.getInt("position")
    }

    private fun query(database: SQLiteDatabase, path: String): WorkspaceFile? = database.rawQuery(
        "SELECT path, content, sha256, version FROM workspace_files WHERE path = ?",
        arrayOf(path),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null
        else WorkspaceFile(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))
    }

    private fun signed(payload: ByteArray): String {
        require(payload.size <= MAX_TOKEN_BYTES)
        return URL_ENCODER.encodeToString(payload) + "." + URL_ENCODER.encodeToString(hmac(payload))
    }

    private fun verified(token: String): ByteArray {
        if (token.length > MAX_TOKEN_CHARS) throw ToolRejectedException("Workspace document ID is invalid")
        val pieces = token.split('.', limit = 3)
        if (pieces.size != 2) throw ToolRejectedException("Workspace document ID is invalid")
        val payload = runCatching { URL_DECODER.decode(pieces[0]) }.getOrNull()
            ?: throw ToolRejectedException("Workspace document ID is invalid")
        val signature = runCatching { URL_DECODER.decode(pieces[1]) }.getOrNull()
            ?: throw ToolRejectedException("Workspace document ID is invalid")
        if (
            payload.size > MAX_TOKEN_BYTES || signature.size != SECRET_BYTES ||
            !MessageDigest.isEqual(signature, hmac(payload))
        ) {
            throw ToolRejectedException("Workspace document ID is invalid")
        }
        return payload
    }

    private fun hmac(payload: ByteArray): ByteArray = Mac.getInstance(HMAC_ALGORITHM).run {
        init(SecretKeySpec(secret, HMAC_ALGORITHM))
        doFinal(payload)
    }

    private fun validatePath(raw: String): String {
        if (
            raw.isBlank() || raw.length > MAX_PATH_CHARS || raw.startsWith('/') || raw.endsWith('/') ||
            '\u0000' in raw || '\\' in raw
        ) {
            throw ToolRejectedException("Workspace path is invalid")
        }
        val pieces = raw.split('/')
        if (pieces.any { it.isBlank() || it == "." || it == ".." }) {
            throw ToolRejectedException("Workspace path is invalid")
        }
        return pieces.joinToString("/")
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DATABASE_NAME = "workspace.sqlite"
        const val DATABASE_VERSION = 1
        const val WORKSPACE_PREFERENCES = "workspace-identity"
        const val SECRET_KEY = "secret"
        const val SECRET_BYTES = 32
        const val MAX_CHANGES = 10
        const val MAX_FILES = 2_048
        const val MAX_PATH_CHARS = 240
        const val MAX_TOKEN_BYTES = 1_024
        const val MAX_TOKEN_CHARS = 4_096
        const val HMAC_ALGORITHM = "HmacSHA256"
        val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
        val URL_DECODER = Base64.getUrlDecoder()
    }
}

internal class WorkspaceAuthority(private val store: WorkspaceStore) {
    val scopeId = ResourceScopeId("private-workspace-v1")
    val tools: List<DeviceTool> = listOf(ListWorkspaceTool(), ReadWorkspaceTool(), ApplyWorkspaceTool())

    fun handles(toolName: String): Boolean = tools.any { it.name == toolName }

    private inner class ListWorkspaceTool : DeviceTool {
        override val definition = ToolDefinition(
            LIST_TOOL,
            "List UTF-8 files in the private transactional workspace.",
            EMPTY_SCHEMA,
        )
        override val effect = ToolEffect.READ

        override suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan {
            requireScope(scopeId)
            strictObject(call.argumentsJson, emptySet())
            return readPlan(call, "List private workspace")
        }

        override suspend fun execute(plan: ToolPlan): ToolResult = withContext(Dispatchers.IO) {
            try {
                requireScope(plan.scopeId)
                val entries = JSONArray()
                var outputBytes = 0
                store.list().forEach { file ->
                    val entry = JSONObject()
                            .put("id", store.encodeId(file.path))
                            .put("name", file.path)
                            .put("type", "document")
                            .put("sizeBytes", file.sizeBytes)
                            .put("sha256", file.sha256)
                    outputBytes += entry.toString().toByteArray(StandardCharsets.UTF_8).size + 1
                    if (outputBytes > MAX_LIST_BYTES) {
                        throw ToolRejectedException("Private workspace listing is too large")
                    }
                    entries.put(entry)
                }
                ToolResult.Success(plan.call.id, JSONObject().put("entries", entries).toString())
            } catch (error: Exception) {
                failed(plan.call, error)
            }
        }
    }

    private inner class ReadWorkspaceTool : DeviceTool {
        override val definition = ToolDefinition(
            READ_TOOL,
            "Read one UTF-8 file segment from the private workspace; pass nextCursor until absent.",
            READ_SCHEMA,
        )
        override val effect = ToolEffect.READ

        override suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan {
            requireScope(scopeId)
            val arguments = strictObject(call.argumentsJson, setOf("documentId", "cursor"))
            val file = store.getById(arguments.requiredString("documentId"))
                ?: throw ToolRejectedException("Workspace file is unavailable")
            arguments.optionalString("cursor")?.let { store.decodeCursor(it, file) }
            return readPlan(call, "Read private workspace file")
        }

        override suspend fun execute(plan: ToolPlan): ToolResult = withContext(Dispatchers.IO) {
            try {
                requireScope(plan.scopeId)
                val arguments = strictObject(plan.call.argumentsJson, setOf("documentId", "cursor"))
                val file = store.getById(arguments.requiredString("documentId"))
                    ?: throw ToolRejectedException("Workspace file is unavailable")
                val position = arguments.optionalString("cursor")?.let { store.decodeCursor(it, file) } ?: 0
                var end = minOf(file.content.length, position + MAX_READ_CHARS)
                if (end < file.content.length && end > position && file.content[end - 1].isHighSurrogate()) end--
                val output = JSONObject()
                    .put("name", file.path)
                    .put("format", "text")
                    .put("sha256", file.sha256)
                    .put("position", position)
                    .put("text", file.content.substring(position, end))
                if (end < file.content.length) output.put("nextCursor", store.encodeCursor(file.path, file.sha256, end))
                ToolResult.Success(plan.call.id, output.toString())
            } catch (error: Exception) {
                failed(plan.call, error)
            }
        }
    }

    private inner class ApplyWorkspaceTool : DeviceTool {
        private val pending = ConcurrentHashMap<String, ResolvedChangeset>()
        private val mutex = Mutex()

        override val definition = ToolDefinition(
            APPLY_TOOL,
            "Create or replace up to ten UTF-8 workspace files atomically. Create uses operation/path/content; replace uses operation/documentId/expectedSha256/content. Android shows one exact diff approval.",
            APPLY_SCHEMA,
        )
        override val effect = ToolEffect.MUTATION

        override suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan =
            withContext(Dispatchers.IO) {
                requireScope(scopeId)
                val changes = parseChanges(call.argumentsJson)
                val diff = buildString {
                    changes.forEach { change ->
                        val existing = store.get(change.path)
                        when (change.operation) {
                            "create" -> if (existing != null) {
                                throw ToolRejectedException("Workspace file ${change.path} already exists")
                            }

                            "replace" -> {
                                if (existing == null || existing.sha256 != change.expectedSha256) {
                                    throw ToolRejectedException("Workspace file ${change.path} is missing or changed")
                                }
                                if (existing.content == change.content) {
                                    throw ToolRejectedException("Workspace file ${change.path} already has that content")
                                }
                            }
                        }
                        append(fullDiff(change.path, existing?.content, change.content))
                    }
                }
                if (diff.toByteArray(StandardCharsets.UTF_8).size > MAX_DIFF_BYTES) {
                    throw ToolRejectedException("Changeset diff is too large to approve safely")
                }
                val fingerprint = fingerprint(call, changes)
                val resolved = ResolvedChangeset(UUID.randomUUID().toString(), changes)
                if (pending.putIfAbsent(fingerprint, resolved) != null) {
                    throw ToolRejectedException("A matching workspace preview is already pending")
                }
                ToolPlan(
                    call,
                    scopeId,
                    effect,
                    "Apply ${changes.size} private workspace change(s)",
                    fingerprint,
                    ApprovalPreview(
                        operation = "Apply workspace changeset",
                        source = changes.joinToString { it.path },
                        destination = "Private transactional workspace",
                        scope = "App-private, backup-excluded storage",
                        conflictBehavior = "Reject the whole changeset if any file changed after preview",
                        diff = diff,
                    ),
                )
            }

        override suspend fun execute(plan: ToolPlan): ToolResult {
            val resolved = pending.remove(plan.fingerprint)
                ?: return ToolResult.Rejected(plan.call.id, "Workspace preview is unavailable or already used")
            return try {
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val files = store.apply(resolved.commitId, resolved.changes)
                        val entries = JSONArray()
                        files.forEach { file ->
                            entries.put(
                                JSONObject().put("id", store.encodeId(file.path))
                                    .put("name", file.path).put("sha256", file.sha256),
                            )
                        }
                        ToolResult.Success(
                            plan.call.id,
                            JSONObject().put("status", "committed").put("files", entries).toString(),
                        )
                    }
                }
            } catch (error: Exception) {
                failed(plan.call, error)
            }
        }

        override fun abandon(plan: ToolPlan) {
            pending.remove(plan.fingerprint)
        }

        override fun recoveryPayload(plan: ToolPlan): String? = pending[plan.fingerprint]?.let {
            JSONObject().put("version", 1).put("commitId", it.commitId).toString()
        }

        override suspend fun reconcile(record: MutationRecord): ToolResult = withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject(record.recoveryPayload)
                if (payload.optInt("version") != 1) throw IllegalArgumentException()
                if (store.hasCommit(payload.getString("commitId"))) {
                    ToolResult.Success(record.callId, JSONObject().put("status", "reconciled_committed").toString())
                } else {
                    ToolResult.Failed(record.callId, "reconciled_rolled_back", "SQLite proves the changeset did not commit")
                }
            } catch (_: Exception) {
                ToolResult.Unknown(record.callId, "Workspace commit could not be reconciled")
            }
        }
    }

    private fun parseChanges(json: String): List<WorkspaceChange> {
        if (json.length > MAX_ARGUMENT_CHARS) throw ToolRejectedException("Workspace changeset is too large")
        val root = strictObject(json, setOf("changes"), validateStrings = false)
        val array = root.optJSONArray("changes")
            ?: throw ToolRejectedException("Workspace changes must be an array")
        if (array.length() !in 1..MAX_CHANGES) throw ToolRejectedException("Workspace changeset size is invalid")
        var totalBytes = 0
        return List(array.length()) { index ->
            val value = array.optJSONObject(index)
                ?: throw ToolRejectedException("Workspace change is invalid")
            val keys = value.keys().asSequence().toSet()
            val operation = value.optString("operation")
            if (operation !in setOf("create", "replace")) {
                throw ToolRejectedException("Workspace operation must be create or replace")
            }
            val expectedKeys = if (operation == "create") setOf("operation", "path", "content")
            else setOf("operation", "documentId", "expectedSha256", "content")
            if (keys != expectedKeys) throw ToolRejectedException("Workspace change does not match its operation")
            val content = value.opt("content") as? String
                ?: throw ToolRejectedException("Workspace content must be UTF-8 text")
            if (!content.isValidUnicode() || '\u0000' in content ||
                content.toByteArray(StandardCharsets.UTF_8).size > MAX_FILE_BYTES
            ) {
                throw ToolRejectedException("Workspace file exceeds the text limit")
            }
            totalBytes += content.toByteArray(StandardCharsets.UTF_8).size
            if (totalBytes > MAX_CHANGESET_BYTES) throw ToolRejectedException("Workspace changeset exceeds 1 MiB")
            if (operation == "create") {
                val path = value.optString("path")
                WorkspaceChange(operation, store.decodeId(store.encodeId(path)), content, null)
            } else {
                val documentId = value.optString("documentId")
                val path = store.decodeId(documentId)
                val expected = value.optString("expectedSha256")
                if (!expected.matches(Regex("[0-9a-f]{64}"))) {
                    throw ToolRejectedException("Expected workspace hash is invalid")
                }
                WorkspaceChange("replace", path, content, expected)
            }
        }.also { changes ->
            if (changes.map { it.path }.distinct().size != changes.size) {
                throw ToolRejectedException("Workspace changeset repeats a path")
            }
        }
    }

    private fun strictObject(
        json: String,
        allowed: Set<String>,
        validateStrings: Boolean = true,
    ): JSONObject {
        if (json.length > MAX_ARGUMENT_CHARS) {
            throw ToolRejectedException("Tool arguments are too large")
        }
        val tokener = JSONTokener(json)
        val value = runCatching { tokener.nextValue() }.getOrNull() as? JSONObject
            ?: throw ToolRejectedException("Tool arguments must be one JSON object")
        if (tokener.nextClean() != '\u0000' || !allowed.containsAll(value.keys().asSequence().toSet())) {
            throw ToolRejectedException("Tool arguments do not match the registered schema")
        }
        if (validateStrings) value.keys().forEach { key ->
            if (value.opt(key) !is String || (value.opt(key) as String).isBlank()) {
                throw ToolRejectedException("Tool argument $key must be a non-empty string")
            }
        }
        return value
    }

    private fun JSONObject.requiredString(name: String): String =
        optionalString(name) ?: throw ToolRejectedException("Tool argument $name is required")

    private fun JSONObject.optionalString(name: String): String? = if (has(name)) opt(name) as? String else null

    private fun readPlan(call: ToolCall, summary: String): ToolPlan = ToolPlan(
        call,
        scopeId,
        ToolEffect.READ,
        summary,
        MessageDigest.getInstance("SHA-256")
            .digest("${call.name}\u0000${call.argumentsJson}".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) },
    )

    private fun fingerprint(call: ToolCall, changes: List<WorkspaceChange>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(call.id.value, call.name, call.argumentsJson).forEach { digest.update(it.toByteArray()) }
        changes.forEach { change ->
            store.get(change.path)?.sha256?.let { digest.update(it.toByteArray()) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fullDiff(path: String, before: String?, after: String): String = buildString {
        append("--- ").append(if (before == null) "/dev/null" else path).append('\n')
        append("+++ ").append(path).append('\n').append("@@ full file @@\n")
        before?.lineSequence()?.forEach { append('-').append(it).append('\n') }
        after.lineSequence().forEach { append('+').append(it).append('\n') }
    }

    private fun requireScope(candidate: ResourceScopeId) {
        if (candidate != scopeId) throw ToolRejectedException("Private workspace scope does not match")
    }

    private fun failed(call: ToolCall, error: Exception): ToolResult = when (error) {
        is CancellationException -> throw error
        is ToolRejectedException -> ToolResult.Rejected(call.id, error.message ?: "Workspace request was rejected")
        else -> ToolResult.Failed(call.id, "workspace_failure", "Private workspace operation failed")
    }

    private data class ResolvedChangeset(val commitId: String, val changes: List<WorkspaceChange>)

    private fun String.isValidUnicode(): Boolean = runCatching {
        StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(this))
    }.isSuccess

    companion object {
        const val LIST_TOOL = "list_workspace_documents"
        const val READ_TOOL = "read_workspace_document"
        const val APPLY_TOOL = "apply_workspace_changes"
        const val MAX_CHANGES = 10
        const val MAX_FILE_BYTES = 256 * 1024
        const val MAX_CHANGESET_BYTES = 1024 * 1024
        const val MAX_DIFF_BYTES = 512 * 1024
        const val MAX_ARGUMENT_CHARS = 1_100_000
        const val MAX_READ_CHARS = 64 * 1024
        const val MAX_LIST_BYTES = 512 * 1024
        const val EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"
        const val READ_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"documentId\":{\"type\":\"string\"},\"cursor\":{\"type\":\"string\"}},\"required\":[\"documentId\"],\"additionalProperties\":false}"
        const val APPLY_SCHEMA = "{\"type\":\"object\",\"properties\":{\"changes\":{" +
            "\"type\":\"array\",\"minItems\":1,\"maxItems\":10,\"items\":{\"oneOf\":[{" +
            "\"type\":\"object\",\"properties\":{\"operation\":{\"type\":\"string\",\"enum\":[\"create\"]}," +
            "\"path\":{\"type\":\"string\",\"maxLength\":240},\"content\":{\"type\":\"string\",\"maxLength\":262144}}," +
            "\"required\":[\"operation\",\"path\",\"content\"],\"additionalProperties\":false},{" +
            "\"type\":\"object\",\"properties\":{\"operation\":{\"type\":\"string\",\"enum\":[\"replace\"]}," +
            "\"documentId\":{\"type\":\"string\",\"maxLength\":4096},\"expectedSha256\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}$\"}," +
            "\"content\":{\"type\":\"string\",\"maxLength\":262144}},\"required\":[\"operation\",\"documentId\",\"expectedSha256\",\"content\"]," +
            "\"additionalProperties\":false}]} }},\"required\":[\"changes\"],\"additionalProperties\":false}"
    }
}
