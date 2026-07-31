package io.github.ciurlaro.codexmobile.app.presentation.state

import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.SessionId

internal fun AppUiState.withSubmittedTurn(
    request: AgentTurnRequest,
    assistantMessageId: String,
    shellCommand: String?,
) = copy(
    statusMessage = if (shellCommand == null) "Thinking" else "Running command",
    messages = messages + ChatMessage(
        id = "user-${request.clientMessageId}",
        role = AgentMessageRole.USER,
        text = request.prompt,
        collaborationMode = request.collaborationMode,
        capabilities = if (shellCommand == null) request.capabilities else emptySet(),
        invocations = if (shellCommand == null) request.invocations else emptyList(),
        model = request.model,
        effort = request.effort,
    ) + ChatMessage(
        id = assistantMessageId,
        role = AgentMessageRole.CODEX,
        text = "",
        isStreaming = true,
        shellCommand = shellCommand,
    ),
    draft = "",
    isTurnActive = true,
    selectedCapabilities = emptySet(),
    selectedInvocations = emptyList(),
    activeSelector = null,
)

internal fun AppUiState.withNewChat() = copy(
    statusMessage = if (isAuthenticated) "Ready" else statusMessage,
    streamedText = "",
    streamedReasoning = "",
    streamedPlan = "",
    planProgress = null,
    hookActivities = emptyList(),
    sessionId = null,
    messages = emptyList(),
    collaborationMode = AgentCollaborationMode.DEFAULT,
    draft = "",
    selectedCapabilities = emptySet(),
    selectedInvocations = emptyList(),
    isHistoryOpen = false,
    screen = AppScreen.CHAT,
    activeSelector = null,
    historySearch = "",
    isConversationLoading = false,
)

internal fun AppUiState.withoutConversation(sessionId: SessionId): AppUiState {
    val updated = copy(
        statusMessage = "Conversation deleted",
        conversations = conversations.filterNot { it.sessionId == sessionId },
        pinnedConversationIds = pinnedConversationIds - sessionId.value,
    )
    return if (this.sessionId == sessionId) {
        updated.withNewChat().copy(statusMessage = "Conversation deleted")
    } else {
        updated
    }
}

internal fun List<ChatMessage>.withStreamingAssistant(
    assistantMessageId: String,
    text: String,
    reasoning: String = "",
    plan: String = "",
    planProgress: io.github.ciurlaro.codexmobile.agent.AgentPlanProgress? = null,
    hookActivities: List<io.github.ciurlaro.codexmobile.agent.AgentHookActivity> = emptyList(),
    isStreaming: Boolean,
    exitCode: Int?,
): List<ChatMessage> = map { message ->
    if (message.id == assistantMessageId) {
        message.copy(
            text = text,
            reasoning = reasoning.takeIf(String::isNotEmpty),
            plan = plan.takeIf(String::isNotEmpty),
            planProgress = planProgress,
            hookActivities = hookActivities,
            isStreaming = isStreaming,
            exitCode = exitCode,
        )
    } else {
        message
    }
}.let { updated ->
    if (isStreaming) updated else updated.filterNot {
        it.id == assistantMessageId && it.text.isEmpty() &&
            it.reasoning.isNullOrEmpty() && it.plan.isNullOrEmpty() && it.shellCommand == null
    }
}
