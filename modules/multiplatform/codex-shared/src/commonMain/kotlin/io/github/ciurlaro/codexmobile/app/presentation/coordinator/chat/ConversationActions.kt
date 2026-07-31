package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.mapper.toChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.state.withNewChat
import io.github.ciurlaro.codexmobile.app.presentation.state.withoutConversation
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun AppViewModel.openHistoryAction() {
    mutableState.update { it.copy(isHistoryOpen = true, activeSelector = null) }
    refreshConversations()
}

internal fun AppViewModel.closeHistoryAction() {
    mutableState.update { it.copy(isHistoryOpen = false, historySearch = "") }
}

internal fun AppViewModel.updateHistorySearchAction(value: String) {
    mutableState.update { it.copy(historySearch = value) }
}

internal fun AppViewModel.startNewChatAction() {
    resetChat(openChat = true)
}

internal fun AppViewModel.resetChatAction(openChat: Boolean = false) {
    serviceController?.let { if (!it.startNewChat()) return }
    pendingConversationId = null
    selectionRestoredSessionId = null
    activeAssistantMessageId = null
    mutableState.update { current ->
        current.withNewChat().let { reset ->
            if (openChat) reset else reset.copy(screen = current.screen)
        }
    }
}

internal fun AppViewModel.openConversationAction(sessionId: SessionId) {
    val controller = serviceController ?: return
    val current = mutableState.value
    if (
        !controller.openConversation(
            sessionId,
            AgentRuntimeSettings(
                approvalPreset = current.approvalPreset,
                serviceTier = current.selectedSpeedTier,
                workingDirectory = platform.activeWorkspacePath(),
            ),
        )
    ) return
    pendingConversationId = sessionId
    selectionRestoredSessionId = null
    activeAssistantMessageId = null
    mutableState.update {
        it.copy(
            sessionId = sessionId,
            messages = emptyList(),
            isHistoryOpen = false,
            activeSelector = null,
            historySearch = "",
            isConversationLoading = true,
        )
    }
    scope.launch {
        try {
            val conversation = controller.readConversation(sessionId)
            if (pendingConversationId == sessionId) {
                val restoredMessages = conversation.messages.map { message -> message.toChatMessage() }
                val collaborationMode = restoredMessages.lastOrNull { message ->
                    message.role == AgentMessageRole.USER && message.shellCommand == null
                }?.collaborationMode ?: AgentCollaborationMode.DEFAULT
                mutableState.update {
                    it.copy(
                        messages = restoredMessages,
                        collaborationMode = collaborationMode,
                        isConversationLoading = false,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (pendingConversationId == sessionId) {
                mutableState.update {
                    it.copy(statusMessage = "Conversation history could not be loaded", isConversationLoading = false)
                }
            }
        }
    }
}

internal fun AppViewModel.togglePinConversationAction(sessionId: SessionId) {
    val current = mutableState.value
    if (current.conversations.none { it.sessionId == sessionId }) return
    val updated = current.pinnedConversationIds.toMutableSet().apply {
        if (!add(sessionId.value)) remove(sessionId.value)
    }.toSet()
    mutableState.update { it.copy(pinnedConversationIds = updated) }
    persistPinnedConversations(updated)
}

internal fun AppViewModel.renameConversationAction(sessionId: SessionId, title: String) {
    val snapshot = title.trim().take(MAX_CONVERSATION_TITLE_LENGTH)
    if (snapshot.isEmpty()) {
        mutableState.update { it.copy(statusMessage = "Conversation name cannot be empty") }
        return
    }
    val controller = serviceController ?: return
    scope.launch {
        try {
            controller.renameConversation(sessionId, snapshot)
            mutableState.update { current ->
                current.copy(
                    statusMessage = "Conversation renamed",
                    conversations = current.conversations.map { conversation ->
                        if (conversation.sessionId == sessionId) conversation.copy(title = snapshot)
                        else conversation
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.update { it.copy(statusMessage = "Conversation could not be renamed") }
        }
    }
}

internal fun AppViewModel.deleteConversationAction(sessionId: SessionId) {
    val controller = serviceController ?: return
    val current = mutableState.value
    if (current.isTurnActive && current.sessionId == sessionId) {
        mutableState.update { it.copy(statusMessage = "Stop the current response before deleting this chat") }
        return
    }
    scope.launch {
        try {
            controller.deleteConversation(sessionId)
            val updatedPins = mutableState.value.pinnedConversationIds - sessionId.value
            persistPinnedConversations(updatedPins)
            if (mutableState.value.sessionId == sessionId) {
                controller.startNewChat()
                pendingConversationId = null
                selectionRestoredSessionId = null
                activeAssistantMessageId = null
            }
            mutableState.update { it.withoutConversation(sessionId) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.update { it.copy(statusMessage = "Conversation could not be deleted") }
        }
    }
}

internal fun AppViewModel.refreshConversationsAction() {
    val controller = serviceController ?: return
    scope.launch {
        try {
            val conversations = controller.listConversations()
            mutableState.update { it.copy(conversations = conversations) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Existing history remains usable when a refresh fails.
        }
    }
}

internal fun AppViewModel.persistPinnedConversationsAction(ids: Set<String>) {
    preferenceState = preferenceState.copy(pinnedConversationIds = ids)
    scope.launch { uiPreferences.savePinnedConversationIds(ids) }
}
