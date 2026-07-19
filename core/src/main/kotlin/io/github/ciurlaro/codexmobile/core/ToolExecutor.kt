package io.github.ciurlaro.codexmobile.core

import kotlinx.coroutines.CancellationException

class ToolExecutor(
    tools: Collection<DeviceTool>,
    private val approvalPolicy: ApprovalPolicy,
) {
    private val toolsByName = tools.associateBy(DeviceTool::name)

    init {
        require(toolsByName.size == tools.size) { "Device tool names must be unique" }
    }

    suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan {
        if (call.id.value.isBlank()) throw ToolRejectedException("Tool call ID is missing")
        val tool = toolsByName[call.name]
            ?: throw ToolRejectedException("Unknown device tool")
        val plan = tool.prepare(call, scopeId)
        check(plan.call == call && plan.scopeId == scopeId && plan.effect == tool.effect) {
            "Device tool returned an inconsistent plan"
        }
        return plan
    }

    suspend fun execute(plan: ToolPlan, approval: UserApproval? = null): ToolResult {
        val tool = toolsByName[plan.call.name]
            ?: return ToolResult.Rejected(plan.call.id, "Device tool is not registered")
        if (plan.effect != tool.effect) {
            return ToolResult.Rejected(plan.call.id, "Device tool plan is invalid")
        }
        when (approvalPolicy.requirement(plan)) {
            ApprovalRequirement.DENY ->
                return ToolResult.Rejected(plan.call.id, "Device policy denied this operation")

            ApprovalRequirement.USER -> if (
                approval?.callId != plan.call.id || approval.planFingerprint != plan.fingerprint
            ) {
                return ToolResult.Rejected(plan.call.id, "Matching user approval is required")
            }

            ApprovalRequirement.ALLOW -> Unit
        }
        return try {
            tool.execute(plan)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ToolResult.Failed(plan.call.id, "tool_failure", "Android tool execution failed")
        }
    }
}
