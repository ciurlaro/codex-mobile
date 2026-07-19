package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.core.ApprovalRequirement
import io.github.ciurlaro.codexmobile.core.DeviceTool
import io.github.ciurlaro.codexmobile.core.MutationRecord
import io.github.ciurlaro.codexmobile.core.MutationRecordId
import io.github.ciurlaro.codexmobile.core.MutationState
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolExecutor
import io.github.ciurlaro.codexmobile.core.ToolResult
import io.github.ciurlaro.codexmobile.core.UserApproval
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class Step04DispatchDeathTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun killWhileProviderDispatchIsInFlight(): Unit = runBlocking {
        assumeTrue(arguments.getString(MODE_ARGUMENT) == HARNESS_MODE)
        requirePhysicalDevice()
        val runId = requiredRunId()
        val database = databaseFile(runId)
        clearFixture(database)

        val platform = AndroidPlatform(context)
        val scope = platform.persistMutationScope(grantTree())
        val rename = platform.deviceTools().single { it.name == RENAME_TOOL }
        val executor = ToolExecutor(
            listOf(rename),
            AndroidMutationJournal(context, database),
        ) { ApprovalRequirement.USER }
        val plan = executor.prepare(
            ToolCall(
                ToolCallId(callId(runId)),
                rename.name,
                JSONObject()
                    .put("documentId", sourceToken(platform, scope))
                    .put("newName", destination(runId))
                    .toString(),
            ),
            scope,
        )
        Step02DocumentsProvider.processDeathRunId = runId
        Step02DocumentsProvider.scenario =
            Step02DocumentsProvider.Scenario.PERSIST_RENAME_AND_BLOCK

        executor.execute(plan, UserApproval.grant(plan))
        error("Expected the app process to be stopped during provider dispatch")
    }

    @Test
    fun verifyInFlightDispatchAfterRestart(): Unit = runBlocking {
        assumeTrue(arguments.getString(MODE_ARGUMENT) == VERIFY_MODE)
        requirePhysicalDevice()
        val runId = requiredRunId()
        val database = databaseFile(runId)
        val journal = AndroidMutationJournal(context, database)
        var scope: ResourceScopeId? = null
        try {
            val executing = journal.unresolved().single { it.callId == ToolCallId(callId(runId)) }
            assertEquals(MutationState.EXECUTING, executing.state)

            val platform = AndroidPlatform(context)
            scope = checkNotNull(platform.currentScopeId())
            val rename = platform.deviceTools().single { it.name == RENAME_TOOL }
            var stateObservedByReconciliation: MutationState? = null
            val observingRename = object : DeviceTool by rename {
                override suspend fun reconcile(record: MutationRecord): ToolResult {
                    stateObservedByReconciliation = record.state
                    return rename.reconcile(record)
                }
            }
            val executor = ToolExecutor(
                listOf(observingRename),
                journal,
            ) { ApprovalRequirement.USER }

            executor.reconcileUnresolved()

            assertEquals(MutationState.UNKNOWN, stateObservedByReconciliation)
            val recovered = checkNotNull(journal.find(executing.id))
            assertEquals(MutationState.SUCCEEDED, recovered.state)
            assertEquals(0, Step02DocumentsProvider.renameCount())
            val names = names(platform, scope)
            assertFalse(SOURCE_NAME in names)
            assertTrue(destination(runId) in names)
            assertTrue(journal.visible().any { it.id == recovered.id })
            journal.acknowledge(recovered.id)
            assertFalse(journal.visible().any { it.id == recovered.id })
        } finally {
            scope?.let { runCatching { AndroidPlatform(context).revokeScope(it) } }
            journal.close()
            clearFixture(database)
        }
    }

    @Test
    fun killAfterProviderFailureBeforeJournalUpdate(): Unit = runBlocking {
        assumeTrue(arguments.getString(MODE_ARGUMENT) == FAILURE_HARNESS_MODE)
        requirePhysicalDevice()
        val runId = requiredRunId()
        val database = failureDatabaseFile(runId)
        clearFixture(database)

        val platform = AndroidPlatform(context)
        val scope = platform.persistMutationScope(grantTree())
        val rename = platform.deviceTools().single { it.name == RENAME_TOOL }
        val call = ToolCall(
            ToolCallId(failureCallId(runId)),
            rename.name,
            JSONObject()
                .put("documentId", sourceToken(platform, scope))
                .put("newName", failureDestination(runId))
                .toString(),
        )
        val plan = rename.prepare(call, scope)
        val record = MutationRecord(
            MutationRecordId("record-${failureCallId(runId)}"),
            call.id,
            call.name,
            scope,
            plan.fingerprint,
            checkNotNull(rename.recoveryPayload(plan)),
            MutationState.PREPARED,
        )
        val journal = AndroidMutationJournal(context, database)
        journal.create(record)
        journal.transition(record.id, MutationState.PREPARED, MutationState.EXECUTING)
        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.REFUSE_RENAME
        assertTrue(rename.execute(plan) is ToolResult.Failed)

        Log.i(PROCESS_DEATH_TAG, "provider failure ready:$runId")
        delay(PROCESS_DEATH_BLOCK_MILLIS)
        error("Expected the app process to be stopped after provider failure")
    }

    @Test
    fun verifyProviderFailureAfterRestart(): Unit = runBlocking {
        assumeTrue(arguments.getString(MODE_ARGUMENT) == FAILURE_VERIFY_MODE)
        requirePhysicalDevice()
        val runId = requiredRunId()
        val database = failureDatabaseFile(runId)
        val journal = AndroidMutationJournal(context, database)
        var scope: ResourceScopeId? = null
        try {
            val executing = journal.unresolved().single {
                it.callId == ToolCallId(failureCallId(runId))
            }
            assertEquals(MutationState.EXECUTING, executing.state)

            val platform = AndroidPlatform(context)
            scope = checkNotNull(platform.currentScopeId())
            val rename = platform.deviceTools().single { it.name == RENAME_TOOL }
            var stateObservedByReconciliation: MutationState? = null
            val observingRename = object : DeviceTool by rename {
                override suspend fun reconcile(record: MutationRecord): ToolResult {
                    stateObservedByReconciliation = record.state
                    return rename.reconcile(record)
                }
            }
            ToolExecutor(
                listOf(observingRename),
                journal,
            ) { ApprovalRequirement.USER }.reconcileUnresolved()

            assertEquals(MutationState.UNKNOWN, stateObservedByReconciliation)
            val recovered = checkNotNull(journal.find(executing.id))
            assertEquals(MutationState.FAILED, recovered.state)
            assertEquals(0, Step02DocumentsProvider.renameCount())
            val names = names(platform, scope)
            assertTrue(SOURCE_NAME in names)
            assertFalse(failureDestination(runId) in names)
            assertTrue(journal.visible().any { it.id == recovered.id })
            journal.acknowledge(recovered.id)
            assertFalse(journal.visible().any { it.id == recovered.id })
        } finally {
            scope?.let { runCatching { AndroidPlatform(context).revokeScope(it) } }
            journal.close()
            clearFixture(database)
        }
    }

    private suspend fun sourceToken(platform: AndroidPlatform, scope: ResourceScopeId): String {
        val root = list(platform, scope)
        val directory = list(platform, scope, tokenFor(root, MUTATION_DIRECTORY))
        return tokenFor(directory, SOURCE_NAME)
    }

    private suspend fun names(platform: AndroidPlatform, scope: ResourceScopeId): Set<String> {
        val root = list(platform, scope)
        val directory = list(platform, scope, tokenFor(root, MUTATION_DIRECTORY))
        val entries = directory.getJSONArray("entries")
        return buildSet {
            repeat(entries.length()) { index ->
                add(entries.getJSONObject(index).getString("name"))
            }
        }
    }

    private suspend fun list(
        platform: AndroidPlatform,
        scope: ResourceScopeId,
        directoryId: String? = null,
    ): JSONObject {
        val tool = platform.deviceTools().single { it.name == "list_documents" }
        val call = ToolCall(
            ToolCallId("step04-dispatch-list"),
            tool.name,
            JSONObject().apply { directoryId?.let { put("directoryId", it) } }.toString(),
        )
        val result = tool.execute(tool.prepare(call, scope))
        check(result is ToolResult.Success)
        return JSONObject(result.outputJson)
    }

    private fun tokenFor(list: JSONObject, name: String): String {
        val entries = list.getJSONArray("entries")
        repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            if (entry.getString("name") == name) return entry.getString("id")
        }
        error("Missing disposable process-death fixture")
    }

    private fun clearFixture(database: File) {
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
        databaseFiles(database).forEach(File::delete)
    }

    private fun grantTree(): Uri = TREE_URI.also { uri ->
        context.grantUriPermission(context.packageName, uri, GRANT_FLAGS)
    }

    private fun requiredRunId(): String =
        requireNotNull(arguments.getString(RUN_ARGUMENT)).takeIf { it.matches(RUN_PATTERN) }
            ?: error("Invalid process-death run")

    private fun databaseFile(runId: String) =
        File(context.noBackupFilesDir, "step04-dispatch-$runId.db")

    private fun failureDatabaseFile(runId: String) =
        File(context.noBackupFilesDir, "step04-provider-failure-$runId.db")

    private fun databaseFiles(database: File) = listOf(
        database,
        File("${database.path}-journal"),
        File("${database.path}-wal"),
        File("${database.path}-shm"),
    )

    private fun callId(runId: String) = "step04-dispatch-$runId"

    private fun destination(runId: String) = "after-$runId.txt"

    private fun failureCallId(runId: String) = "step04-provider-failure-$runId"

    private fun failureDestination(runId: String) = "failed-$runId.txt"

    private fun requirePhysicalDevice() {
        assumeFalse(
            "Process-death evidence requires a physical device",
            Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.PRODUCT.contains("sdk") || Build.HARDWARE.contains("ranchu") ||
                Build.MODEL.contains("Emulator"),
        )
    }

    private companion object {
        const val MODE_ARGUMENT = "step04DispatchMode"
        const val RUN_ARGUMENT = "step04DispatchRun"
        const val HARNESS_MODE = "harness"
        const val VERIFY_MODE = "verify"
        const val FAILURE_HARNESS_MODE = "failure_harness"
        const val FAILURE_VERIFY_MODE = "failure_verify"
        const val RENAME_TOOL = "rename_document"
        const val MUTATION_DIRECTORY = "mutation"
        const val SOURCE_NAME = "before.txt"
        const val PROCESS_DEATH_TAG = "CodexMobileStep04Dispatch"
        const val PROCESS_DEATH_BLOCK_MILLIS = 120_000L
        val RUN_PATTERN = Regex("[a-zA-Z0-9_-]{1,40}")
        val TREE_URI: Uri = DocumentsContract.buildTreeDocumentUri(
            Step02DocumentsProvider.AUTHORITY,
            Step02DocumentsProvider.ROOT_ID,
        )
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
