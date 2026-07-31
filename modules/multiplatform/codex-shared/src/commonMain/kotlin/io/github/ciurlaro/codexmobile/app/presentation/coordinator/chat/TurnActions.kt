package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.input.planCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.state.connectorsNeedingOnUseAuthentication
import io.github.ciurlaro.codexmobile.app.presentation.state.withSubmittedTurn
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.PLAN_CLIENT_MESSAGE_PREFIX
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.update

@OptIn(ExperimentalUuidApi::class)
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
    val workingDirectory = platform.activeWorkspacePath()
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
    val clientMessageId = Uuid.random().toString().let { id ->
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
