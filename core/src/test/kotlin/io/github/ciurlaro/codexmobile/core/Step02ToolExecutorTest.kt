package io.github.ciurlaro.codexmobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class Step02ToolExecutorTest {
    @Test
    fun `defaults to registered Android truth without treating duplicate IDs as replay proof`(): Unit =
        runBlocking {
            val tool = CountingReadTool()
            val executor = ToolExecutor(listOf(tool)) { plan ->
                if (plan.effect == ToolEffect.READ) ApprovalRequirement.ALLOW else ApprovalRequirement.DENY
            }
            val scope = ResourceScopeId("opaque-scope")
            val call = ToolCall(ToolCallId("duplicate"), tool.name, "{}")

            assertFailsWith<ToolRejectedException> {
                executor.prepare(call.copy(name = "unknown"), scope)
            }
            assertFailsWith<ToolRejectedException> {
                executor.prepare(call.copy(id = ToolCallId("")), scope)
            }

            repeat(2) {
                val result = executor.execute(executor.prepare(call, scope))
                assertEquals("{\"observed\":${it + 1}}", assertIs<ToolResult.Success>(result).outputJson)
            }
            assertEquals(2, tool.executions)

            val mutationPlan = ToolPlan(
                call = call.copy(name = "missing-mutation"),
                scopeId = scope,
                effect = ToolEffect.MUTATION,
                summary = "mutation",
                fingerprint = "fingerprint",
            )
            assertIs<ToolResult.Rejected>(executor.execute(mutationPlan))
        }

    private class CountingReadTool : DeviceTool {
        override val definition = ToolDefinition("read", "Read", "{\"type\":\"object\"}")
        override val effect = ToolEffect.READ
        var executions = 0

        override suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan =
            ToolPlan(call, scopeId, effect, "read", "fingerprint")

        override suspend fun execute(plan: ToolPlan): ToolResult.Success {
            executions += 1
            return ToolResult.Success(plan.call.id, "{\"observed\":$executions}")
        }
    }
}
