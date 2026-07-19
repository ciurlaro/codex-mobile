package io.github.ciurlaro.codexmobile.core

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

class ToolExecutor(
    tools: Collection<DeviceTool>,
    private val approvalPolicy: ApprovalPolicy,
) {
    private val toolsByName = tools.associateBy(DeviceTool::name)
    private val preparedPlans = Collections.synchronizedMap(IdentityHashMap<ToolPlan, Boolean>())

    init {
        require(toolsByName.size == tools.size) { "Device tool names must be unique" }
    }

    suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan {
        if (call.id.value.isBlank()) throw ToolRejectedException("Tool call ID is missing")
        val tool = toolsByName[call.name]
            ?: throw ToolRejectedException("Unknown device tool")
        val plan = tool.prepare(call, scopeId)
        try {
            check(plan.call == call && plan.scopeId == scopeId && plan.effect == tool.effect) {
                "Device tool returned an inconsistent plan"
            }
            check(plan.effect != ToolEffect.MUTATION || plan.approvalPreview != null) {
                "Mutation plan is missing its resolved approval preview"
            }
            synchronized(preparedPlans) {
                check(preparedPlans.size < MAX_PREPARED_PLANS) { "Too many device tool plans are pending" }
                preparedPlans[plan] = true
            }
            return plan
        } catch (error: Throwable) {
            tool.abandon(plan)
            throw error
        }
    }

    suspend fun execute(plan: ToolPlan, approval: UserApproval? = null): ToolResult {
        if (preparedPlans.remove(plan) != true) {
            return ToolResult.Rejected(plan.call.id, "Device tool plan was not prepared or was already used")
        }
        val tool = toolsByName[plan.call.name]
            ?: return ToolResult.Rejected(plan.call.id, "Device tool is not registered")
        if (plan.effect != tool.effect) {
            tool.abandon(plan)
            return ToolResult.Rejected(plan.call.id, "Device tool plan is invalid")
        }
        val requirement = approvalPolicy.requirement(plan).let {
            if (plan.effect == ToolEffect.MUTATION && it == ApprovalRequirement.ALLOW) {
                ApprovalRequirement.USER
            } else {
                it
            }
        }
        when (requirement) {
            ApprovalRequirement.DENY -> {
                tool.abandon(plan)
                return ToolResult.Rejected(plan.call.id, "Device policy denied this operation")
            }

            ApprovalRequirement.USER -> if (approval?.consume(plan) != true) {
                tool.abandon(plan)
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

    fun abandon(plan: ToolPlan): Boolean {
        if (preparedPlans.remove(plan) != true) return false
        toolsByName[plan.call.name]?.abandon(plan)
        return true
    }

    private companion object {
        const val MAX_PREPARED_PLANS = 64
    }
}
