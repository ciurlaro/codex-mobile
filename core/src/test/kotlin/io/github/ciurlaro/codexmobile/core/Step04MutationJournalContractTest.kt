package io.github.ciurlaro.codexmobile.core

import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.Test

class Step04MutationJournalContractTest {
    @Test
    fun `permits only the documented mutation state transitions`(): Unit = runBlocking {
        val allowed = setOf(
            MutationState.PREPARED to MutationState.EXECUTING,
            MutationState.EXECUTING to MutationState.SUCCEEDED,
            MutationState.EXECUTING to MutationState.FAILED,
            MutationState.EXECUTING to MutationState.UNKNOWN,
            MutationState.UNKNOWN to MutationState.SUCCEEDED,
            MutationState.UNKNOWN to MutationState.FAILED,
        )
        MutationState.entries.forEach { from ->
            MutationState.entries.forEach { to ->
                assertEquals(from to to in allowed, from.canTransitionTo(to), "$from -> $to")
            }
        }

        val journal = TestMutationJournal()
        val record = record("transition")
        journal.create(record)
        assertFailsWith<IllegalArgumentException> {
            journal.transition(record.id, MutationState.PREPARED, MutationState.SUCCEEDED)
        }
        journal.transition(record.id, MutationState.PREPARED, MutationState.EXECUTING)
        journal.transition(record.id, MutationState.EXECUTING, MutationState.SUCCEEDED)
        assertFailsWith<IllegalArgumentException> {
            journal.transition(record.id, MutationState.SUCCEEDED, MutationState.EXECUTING)
        }
    }

    @Test
    fun `requires durable prepared and executing records before dispatch`(): Unit = runBlocking {
        val journal = TestMutationJournal()
        val tool = RecordingMutationTool().apply {
            beforeExecute = {
                val stored = journal.unresolved().single()
                assertEquals(MutationState.EXECUTING, stored.state)
            }
        }
        val executor = executor(tool, journal)
        val plan = executor.prepare(call("durable", "after.txt"), SCOPE)

        assertIs<ToolResult.Success>(executor.execute(plan, UserApproval.grant(plan)))
        assertEquals(
            listOf(
                "create:durable",
                "transition:PREPARED:EXECUTING",
                "transition:EXECUTING:SUCCEEDED",
            ),
            journal.events,
        )
        assertEquals(1, tool.executions)

        val unavailable = object : MutationJournal by TestMutationJournal() {
            override suspend fun create(record: MutationRecord): Unit = error("unavailable")
        }
        val blockedTool = RecordingMutationTool()
        val blockedExecutor = executor(blockedTool, unavailable)
        val blockedPlan = blockedExecutor.prepare(call("blocked", "blocked.txt"), SCOPE)
        assertIs<ToolResult.Failed>(
            blockedExecutor.execute(blockedPlan, UserApproval.grant(blockedPlan)),
        )
        assertEquals(0, blockedTool.executions)
        assertEquals(1, blockedTool.abandons)
    }

    @Test
    fun `duplicate call IDs preserve distinct intent records`(): Unit = runBlocking {
        val journal = TestMutationJournal()
        val tool = RecordingMutationTool()
        val executor = executor(tool, journal)
        val first = executor.prepare(call("duplicate", "first.txt"), SCOPE)
        val second = executor.prepare(call("duplicate", "second.txt"), SCOPE)

        assertIs<ToolResult.Success>(executor.execute(first, UserApproval.grant(first)))
        assertIs<ToolResult.Success>(executor.execute(second, UserApproval.grant(second)))

        val records = journal.snapshot()
        assertEquals(2, records.size)
        assertEquals(2, records.map(MutationRecord::id).distinct().size)
        assertEquals(setOf(first.fingerprint, second.fingerprint), records.map { it.planFingerprint }.toSet())
        assertTrue(records.all { it.callId == ToolCallId("duplicate") })
    }

    @Test
    fun `retry is denied unless a tool proves a prior no-op`(): Unit = runBlocking {
        val journal = TestMutationJournal()
        val tool = RecordingMutationTool(
            executionResult = { ToolResult.Failed(it.call.id, "unchanged", "Observed unchanged") },
        )
        val executor = executor(tool, journal)
        val first = executor.prepare(call("first", "first.txt"), SCOPE)
        assertIs<ToolResult.Failed>(executor.execute(first, UserApproval.grant(first)))
        val record = journal.snapshot().single()
        val candidate = executor.prepare(call("retry", "first.txt"), SCOPE)

        assertEquals(MutationRetrySafety.NEVER, executor.retrySafety(record, candidate))
        tool.retry = MutationRetrySafety.SAFE_AFTER_PROVEN_NO_OP
        assertEquals(
            MutationRetrySafety.SAFE_AFTER_PROVEN_NO_OP,
            executor.retrySafety(record, candidate),
        )
        assertEquals(1, tool.executions)

        val changed = candidate.copy(fingerprint = "changed")
        assertIs<ToolResult.Rejected>(executor.execute(changed, UserApproval.grant(changed)))
        assertEquals(1, tool.executions)
    }

    @Test
    fun `unknown remains visible and reconciliation never executes again`(): Unit = runBlocking {
        val journal = TestMutationJournal()
        val tool = RecordingMutationTool(
            executionResult = { ToolResult.Unknown(it.call.id, "Ambiguous provider state") },
            reconciliationResult = {
                assertEquals(MutationState.UNKNOWN, it.state)
                ToolResult.Unknown(it.callId, "Still ambiguous")
            },
        )
        val executor = executor(tool, journal)
        val plan = executor.prepare(call("unknown", "after.txt"), SCOPE)

        assertIs<ToolResult.Unknown>(executor.execute(plan, UserApproval.grant(plan)))
        assertEquals(MutationState.UNKNOWN, journal.unresolved().single().state)
        assertEquals(1, executor.reconcileUnresolved().size)
        assertEquals(1, executor.reconcileUnresolved().size)
        assertEquals(1, tool.executions)
        assertEquals(2, tool.reconciliations)

        val record = journal.unresolved().single()
        executor.acknowledgeMutation(record.id)
        assertTrue(executor.visibleMutationRecords().isEmpty())
        assertEquals(record.id, journal.unresolved().single().id)
    }

    private class RecordingMutationTool(
        private val executionResult: (ToolPlan) -> ToolResult = {
            ToolResult.Success(it.call.id, "{}")
        },
        private val reconciliationResult: (MutationRecord) -> ToolResult = {
            ToolResult.Unknown(it.callId, "Still unknown")
        },
    ) : DeviceTool {
        override val definition = ToolDefinition("rename_document", "Rename", "{}")
        override val effect = ToolEffect.MUTATION
        var beforeExecute: suspend () -> Unit = {}
        var executions = 0
        var reconciliations = 0
        var abandons = 0
        var retry = MutationRetrySafety.NEVER

        override suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId) = ToolPlan(
            call,
            scopeId,
            effect,
            "Rename",
            "${call.id.value}:${call.argumentsJson}",
            ApprovalPreview("Rename", "before", "after", "scope", "reject"),
        )

        override suspend fun execute(plan: ToolPlan): ToolResult {
            beforeExecute()
            executions += 1
            return executionResult(plan)
        }

        override fun abandon(plan: ToolPlan) {
            abandons += 1
        }

        override fun recoveryPayload(plan: ToolPlan): String = plan.call.argumentsJson

        override suspend fun reconcile(record: MutationRecord): ToolResult {
            reconciliations += 1
            return reconciliationResult(record)
        }

        override fun retrySafety(
            record: MutationRecord,
            candidate: ToolPlan,
        ): MutationRetrySafety = retry
    }

    private fun executor(tool: DeviceTool, journal: MutationJournal) =
        ToolExecutor(listOf(tool), journal) { ApprovalRequirement.USER }

    private fun call(id: String, destination: String) = ToolCall(
        ToolCallId(id),
        "rename_document",
        "{\"newName\":\"$destination\"}",
    )

    private fun record(id: String) = MutationRecord(
        MutationRecordId(id),
        ToolCallId(id),
        "rename_document",
        SCOPE,
        "fingerprint-$id",
        "{}",
        MutationState.PREPARED,
    )

    private companion object {
        val SCOPE = ResourceScopeId("scope")
    }
}
