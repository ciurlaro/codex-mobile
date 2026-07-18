package io.github.ciurlaro.codexmobile.core

class ToolExecutor(
    private val tools: Collection<DeviceTool>,
    private val approvalPolicy: ApprovalPolicy,
) {
    suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan =
        TODO("Step 02: validate registration, arguments, and scope; then resolve a trustworthy plan")

    suspend fun execute(plan: ToolPlan, approval: UserApproval? = null): ToolResult =
        TODO("Steps 02–03: enforce policy and matching approval before Android dispatch")
}
