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


internal fun AppViewModel.sendMessageAction(): SendMessageOutcome {
    val before = mutableState.value
    val shellCommand = before.draft.shellCommandOrNull()
    val planCommand = if (shellCommand == null) before.draft.planCommandOrNull() else null
    if (
        planCommand != null && planCommand.prompt.isBlank() &&
        before.selectedCapabilities.isEmpty() && before.selectedInvocations.isEmpty()
    ) {
        mutableState.update {
            it.copy(
                draft = "",
                collaborationMode = AgentCollaborationMode.PLAN,
                statusMessage = "Plan mode enabled",
            )
        }
        return SendMessageOutcome.HANDLED
    }
    if (
        before.draft.isBlank() && before.selectedCapabilities.isEmpty() &&
        before.selectedInvocations.isEmpty()
    ) {
        mutableState.update { it.copy(statusMessage = "Enter a message or add a prompt tag") }
        return SendMessageOutcome.HANDLED
    }
    val workingDirectory = container.platform.activeWorkspacePath()
    if (workingDirectory == null) {
        mutableState.update { it.copy(statusMessage = "Select an accessible workspace in Settings") }
        return SendMessageOutcome.WORKSPACE_REQUIRED
    }
    if (beginOnUseAuthentication(before)) return SendMessageOutcome.HANDLED
    val controller = serviceController
    if (controller == null) {
        mutableState.update { it.copy(statusMessage = "Start a background session first") }
        return SendMessageOutcome.HANDLED
    }
    val collaborationMode = if (planCommand != null) {
        AgentCollaborationMode.PLAN
    } else {
        before.collaborationMode
    }
    val clientMessageId = UUID.randomUUID().toString().let { id ->
        if (collaborationMode == AgentCollaborationMode.PLAN) "$PLAN_CLIENT_MESSAGE_PREFIX$id" else id
    }
    val request = AgentTurnRequest(
        prompt = planCommand?.prompt ?: before.draft.trim(),
        clientMessageId = clientMessageId,
        model = before.selectedModel,
        effort = before.selectedEffort,
        serviceTier = before.selectedSpeedTier,
        approvalPreset = before.approvalPreset,
        capabilities = before.selectedCapabilities,
        invocations = before.selectedInvocations,
        workingDirectory = workingDirectory,
        collaborationMode = collaborationMode,
    )
    val submitted = if (shellCommand != null) {
        controller.submitShell(
            shellCommand,
            AgentRuntimeSettings(
                approvalPreset = before.approvalPreset,
                serviceTier = before.selectedSpeedTier,
                workingDirectory = workingDirectory,
            ),
        )
    } else {
        controller.submit(request)
    }
    if (!submitted) return SendMessageOutcome.HANDLED

    val assistantId = "stream-$clientMessageId"
    activeAssistantMessageId = assistantId
    mutableState.update {
        it.copy(collaborationMode = collaborationMode)
            .withSubmittedTurn(request, assistantId, shellCommand)
    }
    return SendMessageOutcome.HANDLED
}

internal fun AppViewModel.togglePlanModeAction() {
    mutableState.update {
        val next = if (it.collaborationMode == AgentCollaborationMode.PLAN) {
            AgentCollaborationMode.DEFAULT
        } else {
            AgentCollaborationMode.PLAN
        }
        it.copy(
            collaborationMode = next,
            statusMessage = if (next == AgentCollaborationMode.PLAN) {
                "Plan mode enabled"
            } else {
                "Default mode enabled"
            },
        )
    }
}

internal fun AppViewModel.proceedWithPlanAction(): SendMessageOutcome {
    mutableState.update {
        it.copy(
            collaborationMode = AgentCollaborationMode.DEFAULT,
            draft = "Implement the proposed plan.",
        )
    }
    return sendMessage()
}

internal fun AppViewModel.updateDraftAction(value: String) {
    mutableState.update { it.copy(draft = value) }
}

internal fun AppViewModel.cancelTurnAction() {
    serviceController?.cancelTurn()
}

internal fun AppViewModel.beginOnUseAuthenticationAction(state: AppUiState): Boolean {
    val pendingPlugin = state.selectedInvocations.filterIsInstance<AgentInvocation.Plugin>()
        .mapNotNull { invocation -> state.plugins.firstOrNull { it.reference.uri == invocation.uri } }
        .firstOrNull { it.reference.id in state.pendingPluginSetups }
    if (pendingPlugin != null) {
        connectPlugin(pendingPlugin.reference)
        mutableState.update { it.copy(statusMessage = "Connect the selected plugin to continue") }
        return true
    }
    val connectors = state.connectorsNeedingOnUseAuthentication()
    if (connectors.isEmpty()) return false
    enqueueConnectorAuthentication(connectors)
    mutableState.update { it.copy(statusMessage = "Connect the selected plugin to continue") }
    return true
}
