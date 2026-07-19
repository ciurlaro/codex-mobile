package io.github.ciurlaro.codexmobile.platform.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.core.ApprovalRequirement
import io.github.ciurlaro.codexmobile.core.MutationRecord
import io.github.ciurlaro.codexmobile.core.MutationRecordId
import io.github.ciurlaro.codexmobile.core.MutationRetrySafety
import io.github.ciurlaro.codexmobile.core.MutationState
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolEffect
import io.github.ciurlaro.codexmobile.core.ToolExecutor
import io.github.ciurlaro.codexmobile.core.ToolPlan
import io.github.ciurlaro.codexmobile.core.ToolResult
import io.github.ciurlaro.codexmobile.core.UserApproval
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Step04MutationRecoveryTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseFiles = mutableListOf<File>()

    @Before
    fun setUp() = clearScopeAndProvider()

    @After
    fun tearDown() {
        clearScopeAndProvider()
        databaseFiles.forEach(::deleteDatabaseFiles)
    }

    @Test
    fun journalDurabilityAtomicityConcurrencyAndStorageFailures(): Unit = runBlocking {
        val durableFile = databaseFile("durable")
        val first = record("durable")
        AndroidMutationJournal(context, durableFile).use { journal ->
            journal.create(first)
            journal.transition(
                first.id,
                MutationState.PREPARED,
                MutationState.EXECUTING,
                "ready",
            )
        }
        AndroidMutationJournal(context, durableFile).use { journal ->
            val reopened = checkNotNull(journal.find(first.id))
            assertEquals(MutationState.EXECUTING, reopened.state)
            assertEquals("ready", reopened.outcome)
            assertTrue(reopened.sequence > 0)
            assertTrue(reopened.createdAtMillis > 0)
            assertTrue(reopened.updatedAtMillis >= reopened.createdAtMillis)

            coroutineScope {
                (0 until 32).map { index ->
                    async(Dispatchers.Default) { journal.create(record("concurrent-$index")) }
                }.awaitAll()
            }
            val unresolved = journal.unresolved()
            assertEquals(33, unresolved.size)
            assertEquals(unresolved.size, unresolved.map(MutationRecord::id).distinct().size)
            assertEquals(unresolved.sortedBy(MutationRecord::sequence), unresolved)
        }

        val lockedFile = databaseFile("locked")
        AndroidMutationJournal(context, lockedFile).use { it.create(record("seed")) }
        val locker = SQLiteDatabase.openDatabase(
            lockedFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        locker.rawQuery("PRAGMA locking_mode=EXCLUSIVE", null).use { it.moveToFirst() }
        locker.beginTransaction()
        try {
            locker.execSQL(
                "UPDATE ${AndroidMutationJournal.TABLE} SET " +
                    "${AndroidMutationJournal.COLUMN_STATE} = ${AndroidMutationJournal.COLUMN_STATE}",
            )
            AndroidMutationJournal(context, lockedFile).use { journal ->
                assertSuspendFails<SQLiteException>("locked") { journal.create(record("locked")) }
            }
        } finally {
            locker.endTransaction()
            locker.close()
        }

        val fullFile = databaseFile("full")
        AndroidMutationJournal(context, fullFile).use { journal ->
            journal.create(record("seed"))
            journal.limitToCurrentPageCount()
            assertSuspendFails<SQLiteException>("full") {
                journal.create(record("full", "x".repeat(60 * 1024)))
            }
        }

        val corruptFile = databaseFile("corrupt")
        corruptFile.writeBytes("not sqlite".toByteArray())
        AndroidMutationJournal(context, corruptFile).use { journal ->
            assertSuspendFails<SQLiteException>("corrupt") { journal.unresolved() }
        }
        assertTrue(corruptFile.exists())

        val blockedParent = databaseFile("not-a-directory").apply { writeText("blocked") }
        AndroidMutationJournal(context, File(blockedParent, "journal.db")).use { journal ->
            assertSuspendFails<SQLiteException>("unavailable") { journal.create(record("unavailable")) }
        }
    }

    @Test
    fun journalSchemaMigrationIsDataSafe(): Unit = runBlocking {
        val upgradeFile = databaseFile("upgrade")
        val raw = SQLiteDatabase.openOrCreateDatabase(upgradeFile, null)
        raw.execSQL(AndroidMutationJournal.CREATE_TABLE_V1)
        raw.insertOrThrow(
            AndroidMutationJournal.TABLE,
            null,
            ContentValues().apply {
                put(AndroidMutationJournal.COLUMN_RECORD_ID, "legacy-record")
                put(AndroidMutationJournal.COLUMN_CALL_ID, "legacy-call")
                put(AndroidMutationJournal.COLUMN_TOOL_NAME, "rename_document")
                put(AndroidMutationJournal.COLUMN_SCOPE_ID, "legacy-scope")
                put(AndroidMutationJournal.COLUMN_FINGERPRINT, "legacy-fingerprint")
                put(AndroidMutationJournal.COLUMN_RECOVERY_PAYLOAD, "{}")
                put(AndroidMutationJournal.COLUMN_STATE, MutationState.PREPARED.name)
            },
        )
        raw.version = 1
        raw.close()

        AndroidMutationJournal(context, upgradeFile).use { journal ->
            val migrated = checkNotNull(journal.find(MutationRecordId("legacy-record")))
            assertEquals(ToolCallId("legacy-call"), migrated.callId)
            assertEquals(MutationState.PREPARED, migrated.state)
            assertEquals(0, migrated.createdAtMillis)
            assertFalse(migrated.acknowledged)
            journal.transition(
                migrated.id,
                MutationState.PREPARED,
                MutationState.EXECUTING,
            )
        }

        SQLiteDatabase.openDatabase(
            upgradeFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { it.version = 3 }
        AndroidMutationJournal(context, upgradeFile).use { journal ->
            assertSuspendFails<SQLiteException>("downgrade") { journal.visible() }
        }
        SQLiteDatabase.openDatabase(
            upgradeFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            assertEquals(3, database.version)
            database.rawQuery(
                "SELECT COUNT(*) FROM ${AndroidMutationJournal.TABLE}",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun terminationAtEveryMutationBoundaryReconcilesTruthfully(): Unit = runBlocking {
        var fixture = fixture("before-prepared")
        val before = fixture.prepare("before-prepared.txt", "before-prepared")
        fixture.executor.abandon(before)
        assertTrue(fixture.journal.unresolved().isEmpty())
        assertEquals(0, Step02DocumentsProvider.renameCount())
        fixture.close()

        clearScopeAndProvider()
        fixture = fixture("prepared")
        val prepared = fixture.prepare("prepared.txt", "prepared")
        fixture.seed(prepared, MutationState.PREPARED)
        fixture = fixture.reopen()
        fixture.executor.reconcileUnresolved()
        assertEquals(MutationState.FAILED, fixture.singleRecord().state)
        assertEquals(0, Step02DocumentsProvider.renameCount())
        fixture.close()

        clearScopeAndProvider()
        fixture = fixture("executing")
        val executing = fixture.prepare("executing.txt", "executing")
        fixture.seed(executing, MutationState.EXECUTING)
        fixture = fixture.reopen()
        fixture.executor.reconcileUnresolved()
        assertEquals(MutationState.FAILED, fixture.singleRecord().state)
        assertEquals(0, Step02DocumentsProvider.renameCount())
        fixture.close()

        clearScopeAndProvider()
        fixture = fixture("provider-success")
        val success = fixture.prepare("after.txt", "provider-success")
        fixture.seed(success, MutationState.EXECUTING)
        assertResult<ToolResult.Success>(fixture.renameTool.execute(success))
        fixture = fixture.reopen()
        fixture.executor.reconcileUnresolved()
        assertEquals(MutationState.SUCCEEDED, fixture.singleRecord().state)
        assertEquals(1, Step02DocumentsProvider.renameCount())
        fixture.close()

        clearScopeAndProvider()
        fixture = fixture("provider-failure")
        val failure = fixture.prepare("after.txt", "provider-failure")
        fixture.seed(failure, MutationState.EXECUTING)
        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.REFUSE_RENAME
        assertResult<ToolResult.Failed>(fixture.renameTool.execute(failure))
        fixture = fixture.reopen()
        fixture.executor.reconcileUnresolved()
        assertEquals(MutationState.FAILED, fixture.singleRecord().state)
        assertEquals(1, Step02DocumentsProvider.renameCount())
        fixture.close()
    }

    @Test
    fun reconciliationCoversAllObservedDocumentStatesAndPermissionLoss(): Unit = runBlocking {
        suspend fun reconcileScenario(
            name: String,
            scenario: Step02DocumentsProvider.Scenario,
            expected: MutationState,
        ) {
            clearScopeAndProvider()
            var fixture = fixture(name)
            val plan = fixture.prepare("after.txt", name)
            fixture.seed(plan, MutationState.EXECUTING)
            Step02DocumentsProvider.scenario = scenario
            fixture.renameTool.execute(plan)
            fixture = fixture.reopen()
            fixture.executor.reconcileUnresolved()
            assertEquals(expected, fixture.singleRecord().state)
            val dispatches = Step02DocumentsProvider.renameCount()
            fixture.executor.reconcileUnresolved()
            fixture.executor.reconcileUnresolved()
            assertEquals(dispatches, Step02DocumentsProvider.renameCount())
            fixture.close()
        }

        reconcileScenario("unchanged", Step02DocumentsProvider.Scenario.REFUSE_RENAME, MutationState.FAILED)
        reconcileScenario("destination", Step02DocumentsProvider.Scenario.NORMAL, MutationState.SUCCEEDED)
        reconcileScenario("both", Step02DocumentsProvider.Scenario.PARTIAL_RENAME, MutationState.UNKNOWN)
        reconcileScenario("neither", Step02DocumentsProvider.Scenario.DELETE_AFTER_DISPATCH, MutationState.UNKNOWN)

        clearScopeAndProvider()
        var revoked = fixture("revoked")
        val revokedPlan = revoked.prepare("after.txt", "revoked")
        revoked.seed(revokedPlan, MutationState.EXECUTING)
        val persisted = context.contentResolver.persistedUriPermissions.single { it.uri == TREE_URI }
        var flags = 0
        if (persisted.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (persisted.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.releasePersistableUriPermission(TREE_URI, flags)
        revoked = revoked.reopen()
        revoked.executor.reconcileUnresolved()
        assertEquals(MutationState.UNKNOWN, revoked.singleRecord().state)
        assertEquals(0, Step02DocumentsProvider.renameCount())
        revoked.close()
    }

    @Test
    fun retryRulesRejectStaleApprovalAndGenericReplay(): Unit = runBlocking {
        val fixture = fixture("retry")
        val completed = fixture.prepare("after.txt", "completed")
        val result = fixture.executor.execute(completed, UserApproval.grant(completed))
        assertResult<ToolResult.Success>(result)
        val record = fixture.journal.find(fixture.allRecords().single().id)
        checkNotNull(record)
        assertEquals(
            setOf(
                "version",
                "parentPath",
                "parentId",
                "sourceId",
                "sourceName",
                "destinationName",
            ),
            JSONObject(record.recoveryPayload).keys().asSequence().toSet(),
        )
        val candidate = fixture.prepare("retry.txt", "fresh-retry")

        assertEquals(MutationRetrySafety.NEVER, fixture.executor.retrySafety(record, candidate))
        val altered = candidate.copy(fingerprint = "altered")
        assertResult<ToolResult.Rejected>(
            fixture.executor.execute(altered, UserApproval.grant(altered)),
        )
        assertEquals(1, Step02DocumentsProvider.renameCount())
        fixture.close()
    }

    @Test
    fun unknownVisibilityPrivacyAndRetentionRemainCorrect(): Unit = runBlocking {
        var now = 10L
        val file = databaseFile("maintenance")
        AndroidMutationJournal(context, file) { now }.use { journal ->
            val pending = record("pending", "{\"version\":1}")
            journal.create(pending)
            assertEquals(pending.id, journal.visible().single().id)
            journal.acknowledge(pending.id)
            assertTrue(journal.visible().isEmpty())
            assertEquals(pending.id, journal.unresolved().single().id)

            val unknown = record("unknown", "{\"version\":1}")
            journal.create(unknown)
            journal.transition(unknown.id, MutationState.PREPARED, MutationState.EXECUTING)
            journal.transition(unknown.id, MutationState.EXECUTING, MutationState.UNKNOWN)
            assertEquals(unknown.id, journal.visible().single().id)

            val resolved = record("resolved", "{\"version\":1}")
            journal.create(resolved)
            journal.transition(resolved.id, MutationState.PREPARED, MutationState.EXECUTING)
            journal.transition(
                resolved.id,
                MutationState.EXECUTING,
                MutationState.SUCCEEDED,
                acknowledged = true,
            )
            now = 20L
            assertEquals(1, journal.pruneResolved(20))
            assertNull(journal.find(resolved.id))
            assertTrue(journal.unresolved().map(MutationRecord::id).containsAll(listOf(pending.id, unknown.id)))

            val stored = checkNotNull(journal.find(unknown.id))
            assertFalse(stored.recoveryPayload.contains("content", ignoreCase = true))
            assertFalse(stored.recoveryPayload.contains("credential", ignoreCase = true))
            assertFalse(stored.recoveryPayload.contains("token", ignoreCase = true))
        }
    }

    private suspend fun fixture(name: String): RecoveryFixture {
        val platform = AndroidPlatform(context)
        val scope = platform.currentScopeId() ?: platform.persistMutationScope(grantTree())
        val root = list(platform, scope)
        val directoryToken = tokenFor(root, "mutation")
        val directory = list(platform, scope, directoryToken)
        val sourceToken = tokenFor(directory, "before.txt")
        val journal = AndroidMutationJournal(context, databaseFile(name))
        return RecoveryFixture(platform, scope, sourceToken, journal)
    }

    private suspend fun list(
        platform: AndroidPlatform,
        scope: ResourceScopeId,
        directoryId: String? = null,
    ): JSONObject {
        val tool = platform.deviceTools().single { it.name == "list_documents" }
        val call = ToolCall(
            ToolCallId("list-${directoryId.orEmpty().hashCode()}"),
            tool.name,
            JSONObject().apply { directoryId?.let { put("directoryId", it) } }.toString(),
        )
        return JSONObject(assertResult<ToolResult.Success>(tool.execute(tool.prepare(call, scope))).outputJson)
    }

    private fun tokenFor(list: JSONObject, name: String): String {
        val entries = list.getJSONArray("entries")
        repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            if (entry.getString("name") == name) return entry.getString("id")
        }
        error("Missing disposable test document")
    }

    private inner class RecoveryFixture(
        val platform: AndroidPlatform,
        val scope: ResourceScopeId,
        val sourceToken: String,
        val journal: AndroidMutationJournal,
    ) {
        val renameTool = platform.deviceTools().single { it.name == "rename_document" }
        val executor = ToolExecutor(platform.deviceTools(), journal) { plan ->
            if (plan.effect == ToolEffect.MUTATION) ApprovalRequirement.USER else ApprovalRequirement.ALLOW
        }

        suspend fun prepare(destination: String, callId: String): ToolPlan = executor.prepare(
            ToolCall(
                ToolCallId(callId),
                renameTool.name,
                JSONObject().put("documentId", sourceToken).put("newName", destination).toString(),
            ),
            scope,
        )

        suspend fun seed(plan: ToolPlan, state: MutationState) {
            val record = MutationRecord(
                MutationRecordId("record-${plan.call.id.value}"),
                plan.call.id,
                plan.call.name,
                plan.scopeId,
                plan.fingerprint,
                checkNotNull(renameTool.recoveryPayload(plan)),
                MutationState.PREPARED,
            )
            journal.create(record)
            if (state == MutationState.EXECUTING) {
                journal.transition(record.id, MutationState.PREPARED, MutationState.EXECUTING)
            }
        }

        suspend fun reopen(): RecoveryFixture {
            journal.close()
            val replacement = AndroidPlatform(context)
            return RecoveryFixture(
                replacement,
                scope,
                sourceToken,
                AndroidMutationJournal(context, databaseFiles.last()),
            )
        }

        suspend fun singleRecord(): MutationRecord = allRecords().single()

        suspend fun allRecords(): List<MutationRecord> = journal.allRecords()

        fun close() = journal.close()
    }

    private fun record(id: String, payload: String = "{}") = MutationRecord(
        MutationRecordId("record-$id"),
        ToolCallId("call-$id"),
        "rename_document",
        ResourceScopeId("scope-$id"),
        "fingerprint-$id",
        payload,
        MutationState.PREPARED,
    )

    private fun databaseFile(name: String): File =
        File(context.cacheDir, "step04-$name-${UUID.randomUUID()}.db").also(databaseFiles::add)

    private fun deleteDatabaseFiles(file: File) {
        listOf(file, File("${file.path}-journal"), File("${file.path}-wal"), File("${file.path}-shm"))
            .forEach(File::delete)
    }

    private fun grantTree(): Uri = TREE_URI.also { uri ->
        context.grantUriPermission(context.packageName, uri, GRANT_FLAGS)
    }

    private fun clearScopeAndProvider() {
        Step02DocumentsProvider.reset()
        context.getSharedPreferences("resource_scope", Context.MODE_PRIVATE).edit().clear().commit()
        context.contentResolver.persistedUriPermissions.toList()
            .filter { it.uri.authority == Step02DocumentsProvider.AUTHORITY }
            .forEach { permission ->
                var flags = 0
                if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                if (flags != 0) runCatching {
                    context.contentResolver.releasePersistableUriPermission(permission.uri, flags)
                }
            }
        context.revokeUriPermission(
            TREE_URI,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    private suspend inline fun <reified T : Throwable> assertSuspendFails(
        label: String = "operation",
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
            @Suppress("UNCHECKED_CAST")
            return error as T
        }
        throw AssertionError("Expected ${T::class.java.name} for $label")
    }

    private inline fun <reified T> assertResult(value: Any?): T {
        assertTrue("Expected ${T::class.java.name}, got ${value?.javaClass?.name}", value is T)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private companion object {
        val TREE_URI: Uri = DocumentsContract.buildTreeDocumentUri(
            Step02DocumentsProvider.AUTHORITY,
            Step02DocumentsProvider.ROOT_ID,
        )
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
