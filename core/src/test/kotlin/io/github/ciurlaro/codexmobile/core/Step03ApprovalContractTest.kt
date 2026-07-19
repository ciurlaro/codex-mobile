package io.github.ciurlaro.codexmobile.core

import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.Test

class Step03ApprovalContractTest {
    @Test
    fun `mutations require user approval by default`(): Unit = runBlocking {
        val tool = RecordingMutationTool()
        val executor = ToolExecutor(listOf(tool)) { ApprovalRequirement.ALLOW }
        val denied = executor.execute(executor.prepare(call("1"), SCOPE))
        assertIs<ToolResult.Rejected>(denied)
        assertEquals(0, tool.executions)
        assertEquals(1, tool.abandons)

        val approvedPlan = executor.prepare(call("2"), SCOPE)
        assertIs<ToolResult.Success>(executor.execute(approvedPlan, UserApproval.grant(approvedPlan)))
        assertEquals(1, tool.executions)
    }

    @Test
    fun `unknown tool and cross-scope plan fail closed`(): Unit = runBlocking {
        val tool = RecordingMutationTool()
        val executor = ToolExecutor(listOf(tool)) { ApprovalRequirement.USER }
        assertFailsWith<ToolRejectedException> {
            runBlocking { executor.prepare(ToolCall(ToolCallId("1"), "unknown", "{}"), SCOPE) }
        }

        val prepared = executor.prepare(call("2"), SCOPE)
        val forged = prepared.copy(scopeId = ResourceScopeId("other"))
        assertIs<ToolResult.Rejected>(executor.execute(forged, UserApproval.grant(forged)))
        assertEquals(0, tool.executions)
    }

    @Test
    fun `approval must match call and resolved plan fingerprint`(): Unit = runBlocking {
        val tool = RecordingMutationTool()
        val executor = ToolExecutor(listOf(tool)) { ApprovalRequirement.USER }
        val first = executor.prepare(call("1"), SCOPE)
        val second = executor.prepare(call("2"), SCOPE)
        val approval = UserApproval.grant(first)

        assertIs<ToolResult.Rejected>(executor.execute(second, approval))
        assertIs<ToolResult.Rejected>(executor.execute(first, approval))
        assertEquals(0, tool.executions)
    }

    @Test
    fun `approval is consumed once and cannot authorize altered intent`(): Unit = runBlocking {
        val tool = RecordingMutationTool()
        val executor = ToolExecutor(listOf(tool)) { ApprovalRequirement.USER }
        val plan = executor.prepare(call("1"), SCOPE)
        val approval = UserApproval.grant(plan)

        assertIs<ToolResult.Success>(executor.execute(plan, approval))
        assertIs<ToolResult.Rejected>(executor.execute(plan, approval))
        assertIs<ToolResult.Rejected>(executor.execute(plan.copy(fingerprint = "altered"), UserApproval.grant(plan)))
        assertEquals(1, tool.executions)
    }

    @Test
    fun `duplicate call ID never implies replay safety`(): Unit = runBlocking {
        val tool = RecordingMutationTool()
        val executor = ToolExecutor(listOf(tool)) { ApprovalRequirement.USER }
        val first = executor.prepare(call("duplicate"), SCOPE)
        val second = executor.prepare(call("duplicate"), SCOPE)

        assertIs<ToolResult.Success>(executor.execute(first, UserApproval.grant(first)))
        assertIs<ToolResult.Success>(executor.execute(second, UserApproval.grant(second)))
        assertEquals(2, tool.executions)
    }

    @Test
    fun `abandoned approval plan cannot execute later`(): Unit = runBlocking {
        val tool = RecordingMutationTool()
        val executor = ToolExecutor(listOf(tool)) { ApprovalRequirement.USER }
        val plan = executor.prepare(call("abandoned"), SCOPE)

        assertEquals(true, executor.abandon(plan))
        assertEquals(false, executor.abandon(plan))
        assertIs<ToolResult.Rejected>(executor.execute(plan, UserApproval.grant(plan)))
        assertEquals(1, tool.abandons)
        assertEquals(0, tool.executions)
    }

    private class RecordingMutationTool : DeviceTool {
        override val definition = ToolDefinition("rename_document", "Rename", "{}")
        override val effect = ToolEffect.MUTATION
        var executions = 0
        var abandons = 0

        override suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId) = ToolPlan(
            call = call,
            scopeId = scopeId,
            effect = effect,
            summary = "Rename document",
            fingerprint = "${call.id.value}:${call.argumentsJson}",
            approvalPreview = ApprovalPreview(
                operation = "Rename document",
                source = "before.txt",
                destination = "after.txt",
                scope = "Selected disposable folder",
                conflictBehavior = "Reject if destination exists",
            ),
        )

        override suspend fun execute(plan: ToolPlan): ToolResult {
            executions += 1
            return ToolResult.Success(plan.call.id, "{}")
        }

        override fun abandon(plan: ToolPlan) {
            abandons += 1
        }
    }

    private fun call(id: String) = ToolCall(ToolCallId(id), "rename_document", "{\"newName\":\"after.txt\"}")

    private companion object {
        val SCOPE = ResourceScopeId("scope")
    }
}
