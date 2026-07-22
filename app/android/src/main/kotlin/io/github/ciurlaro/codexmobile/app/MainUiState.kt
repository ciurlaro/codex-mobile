package io.github.ciurlaro.codexmobile.app

import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.platform.android.TelegramAuthPrompt

data class MainUiState(
    val statusMessage: String = "Ready to sign in",
    val streamedText: String = "",
    val sessionId: SessionId? = null,
    val isAuthenticated: Boolean = false,
    val conversations: List<AgentConversationSummary> = emptyList(),
    val pinnedConversationIds: Set<String> = emptySet(),
    val messages: List<ChatMessage> = emptyList(),
    val models: List<AgentModel> = emptyList(),
    val draft: String = "",
    val selectedCapabilities: Set<AgentCapability> = emptySet(),
    val selectedModel: String? = null,
    val selectedEffort: String? = null,
    val selectedSpeedTier: String? = null,
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.NEVER,
    val isHistoryOpen: Boolean = false,
    val screen: AppScreen = AppScreen.CHAT,
    val activeSelector: ChatSelector? = null,
    val historySearch: String = "",
    val isConversationLoading: Boolean = false,
    val signInUrl: String? = null,
    val isAuthenticationInProgress: Boolean = false,
    val isTurnActive: Boolean = false,
    val hasStorageAccess: Boolean = false,
    val workspacePath: String? = null,
    val codexApproval: AgentEvent.ApprovalRequested? = null,
    val isBackgroundActive: Boolean = false,
    val isBackgroundNotificationVisible: Boolean = true,
    val isTelegramAvailable: Boolean = false,
    val isTelegramConnected: Boolean = false,
    val telegramUsername: String? = null,
    val telegramAuthPrompt: TelegramAuthPrompt? = null,
    val isTelegramOperationInProgress: Boolean = false,
    val telegramError: String? = null,
)

internal fun MainUiState.withSubmittedTurn(
    request: AgentTurnRequest,
    assistantMessageId: String,
    shellCommand: String?,
) = copy(
    statusMessage = if (shellCommand == null) "Thinking" else "Running command",
    messages = messages + ChatMessage(
        id = "user-${request.clientMessageId}",
        role = AgentMessageRole.USER,
        text = request.prompt,
        capabilities = if (shellCommand == null) request.capabilities else emptySet(),
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
    selectedCapabilities = emptySet(),
    activeSelector = null,
)

internal fun MainUiState.withNewChat() = copy(
    statusMessage = if (isAuthenticated) "Ready" else statusMessage,
    streamedText = "",
    sessionId = null,
    messages = emptyList(),
    draft = "",
    selectedCapabilities = emptySet(),
    isHistoryOpen = false,
    screen = AppScreen.CHAT,
    activeSelector = null,
    historySearch = "",
    isConversationLoading = false,
)

internal fun MainUiState.withoutConversation(sessionId: SessionId): MainUiState {
    val remaining = conversations.filterNot { it.sessionId == sessionId }
    val remainingPins = pinnedConversationIds - sessionId.value
    val updated = copy(
        statusMessage = "Conversation deleted",
        conversations = remaining,
        pinnedConversationIds = remainingPins,
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
    isStreaming: Boolean,
    exitCode: Int?,
): List<ChatMessage> = map { message ->
    if (message.id == assistantMessageId) {
        message.copy(text = text, isStreaming = isStreaming, exitCode = exitCode)
    } else {
        message
    }
}.let { updated ->
    if (isStreaming) updated else updated.filterNot {
        it.id == assistantMessageId && it.text.isEmpty() && it.shellCommand == null
    }
}

internal fun MainUiState.selectedModelOrNull(): AgentModel? =
    models.firstOrNull { it.id == selectedModel }
