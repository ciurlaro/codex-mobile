package io.github.ciurlaro.codexmobile.core

class SessionController(
    private val agentClient: AgentClient,
    private val toolExecutor: ToolExecutor,
) {
    suspend fun start(previous: SessionId? = null): SessionId =
        TODO("Step 01: keep this responsibility in the ViewModel unless coordination pressure proves it useful")

    suspend fun submit(sessionId: SessionId, prompt: String): Unit =
        TODO("Step 01: validate UI intent and submit one turn")

    suspend fun approve(
        sessionId: SessionId,
        plan: ToolPlan,
        approval: UserApproval,
    ): ToolResult =
        TODO("Step 03: execute the exact approved plan and return Android's result to the agent")
}
