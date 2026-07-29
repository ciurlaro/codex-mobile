package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.app.lifecycle.CodexMobileApplication
import io.github.ciurlaro.codexmobile.app.persistence.AppPreferencesStore
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.planCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.withoutActiveInvocationToken
import io.github.ciurlaro.codexmobile.app.presentation.invocation.withRecentInvocation
import io.github.ciurlaro.codexmobile.app.presentation.mapper.toChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionActionError
import io.github.ciurlaro.codexmobile.app.presentation.model.CustomExtensionSource
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.afterExpiry
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionRemoval
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_URL
import io.github.ciurlaro.codexmobile.app.presentation.model.OPENAI_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.canonicalPluginSourceId
import io.github.ciurlaro.codexmobile.app.presentation.model.enabledMarketplaceNames
import io.github.ciurlaro.codexmobile.app.presentation.model.initialExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.reconcilePendingPluginSetups
import io.github.ciurlaro.codexmobile.app.presentation.state.withNewChat
import io.github.ciurlaro.codexmobile.app.presentation.state.withStreamingAssistant
import io.github.ciurlaro.codexmobile.app.presentation.state.withSubmittedTurn
import io.github.ciurlaro.codexmobile.app.presentation.state.withoutConversation
import io.github.ciurlaro.codexmobile.app.presentation.state.connectorsNeedingOnUseAuthentication
import io.github.ciurlaro.codexmobile.app.presentation.state.selectedModelOrNull
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionController
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionState
import io.github.ciurlaro.codexmobile.app.session.background.CodexForegroundService
import io.github.ciurlaro.codexmobile.app.session.background.CodexServiceConnection
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.PLAN_CLIENT_MESSAGE_PREFIX
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginSummary
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.SessionId
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull


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
                workingDirectory = container.platform.activeWorkspacePath(),
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
    uiPreferences.savePinnedConversationIds(ids)
}
