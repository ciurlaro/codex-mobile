@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.agent.AgentClient
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentConversation
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentElicitation
import io.github.ciurlaro.codexmobile.agent.AgentElicitationAction
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentHookCatalog
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.agent.AgentPluginDetail
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackageCatalog
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull


internal fun CodexSessionController.authenticateAction() {
    val shouldStart = !closed.load() && authenticationStarted.compareAndSet(false, true)
    if (!shouldStart) return
    mutableState.update {
        it.copy(
            statusMessage = "Checking sign-in…",
            signInUrl = null,
            attentionRequired = false,
            diagnosticCode = null,
        )
    }
    launchVisibleFailure(resetAuthentication = true) { agentClient.authenticate() }
}

internal fun CodexSessionController.cancelAuthenticationAction() {
    if (closed.load()) return
    mutableState.update { it.copy(statusMessage = "Cancelling sign-in…") }
    launchVisibleFailure(resetAuthentication = true) {
        agentClient.cancelAuthentication()
        authenticationStarted.store(false)
        mutableState.update {
            it.copy(statusMessage = "Ready to sign in", signInUrl = null)
        }
    }
}

internal fun CodexSessionController.submitAction(request: AgentTurnRequest): Boolean {
    if (request.prompt.isBlank() && request.capabilities.isEmpty() && request.invocations.isEmpty()) {
        mutableState.update { it.copy(statusMessage = "Enter a prompt first") }
        return false
    }
    if (!beginTurn("Codex is responding…")) return false
    launchVisibleFailure(resetTurn = true) {
        val sessionId = state.value.sessionId ?: agentClient.openSession(
            settings = AgentRuntimeSettings(
                approvalPreset = request.approvalPreset,
                serviceTier = request.serviceTier,
                workingDirectory = request.workingDirectory,
            ),
        )
        agentClient.sendTurn(sessionId, request)
        turnStartCompleted.store(true)
        if (cancellationStarted.load()) dispatchCancellation(sessionId)
    }
    return true
}

internal fun CodexSessionController.submitShellAction(command: String, settings: AgentRuntimeSettings): Boolean {
    if (command.isBlank()) {
        mutableState.update { it.copy(statusMessage = "Enter a shell command after !") }
        return false
    }
    if (!beginTurn("Running command…")) return false
    launchVisibleFailure(resetTurn = true) {
        val sessionId = agentClient.openSession(state.value.sessionId, settings)
        agentClient.runShellCommand(sessionId, command)
        turnStartCompleted.store(true)
        if (cancellationStarted.load()) dispatchCancellation(sessionId)
    }
    return true
}

internal fun CodexSessionController.beginTurnAction(statusMessage: String): Boolean {
    if (!state.value.isAuthenticated) {
        mutableState.update { it.copy(statusMessage = "Sign in before sending a message") }
        return false
    }
    if (state.value.externalOperation != null) {
        mutableState.update { it.copy(statusMessage = "Wait for the extension change to finish") }
        return false
    }
    if (closed.load() || !turnClaimed.compareAndSet(false, true)) return false
    cancellationStarted.store(false)
    cancellationDispatched.store(false)
    turnStartCompleted.store(false)
    mutableState.update {
        it.copy(
            statusMessage = statusMessage,
            streamedText = "",
            streamedReasoning = "",
            streamedPlan = "",
            planItemId = null,
            planProgress = null,
            hookActivities = emptyList(),
            reasoningItemId = null,
            reasoningSummaryIndex = null,
            shellExitCode = null,
            isTurnActive = true,
            attentionRequired = false,
            diagnosticCode = null,
        )
    }
    return true
}

internal fun CodexSessionController.resolveApprovalAction(requestId: String, decision: AgentApprovalDecision) {
    val pending = state.value.pendingApproval ?: return
    if (pending.requestId != requestId || closed.load()) return
    mutableState.update {
        it.copy(
            statusMessage = if (decision == AgentApprovalDecision.ACCEPT) "Continuing…" else "Declining…",
            pendingApproval = null,
            attentionRequired = false,
        )
    }
    launchVisibleFailure { agentClient.resolveApproval(requestId, decision) }
}

internal fun CodexSessionController.resolveElicitationAction(requestId: String, response: AgentElicitationResponse) {
    val pending = state.value.pendingElicitation ?: return
    if (pending.requestId != requestId || closed.load()) return
    mutableState.update { it.copy(pendingElicitation = null, attentionRequired = false) }
    launchVisibleFailure { agentClient.resolveElicitation(requestId, response) }
}

internal fun CodexSessionController.startNewChatAction(): Boolean {
    if (!state.value.isAuthenticated || state.value.isTurnActive || closed.load()) return false
    mutableState.update {
        it.copy(
            statusMessage = "Ready",
            streamedText = "",
            streamedReasoning = "",
            streamedPlan = "",
            planItemId = null,
            planProgress = null,
            hookActivities = emptyList(),
            reasoningItemId = null,
            reasoningSummaryIndex = null,
            sessionId = null,
            diagnosticCode = null,
            attentionRequired = false,
        )
    }
    return true
}

internal fun CodexSessionController.openConversationAction(
    sessionId: SessionId,
    settings: AgentRuntimeSettings = AgentRuntimeSettings(),
): Boolean {
    if (!state.value.isAuthenticated || state.value.isTurnActive || closed.load()) return false
    mutableState.update {
        it.copy(
            statusMessage = "Loading conversation…",
            streamedText = "",
            streamedReasoning = "",
            streamedPlan = "",
            planItemId = null,
            planProgress = null,
            hookActivities = emptyList(),
            reasoningItemId = null,
            reasoningSummaryIndex = null,
            diagnosticCode = null,
        )
    }
    launchVisibleFailure { agentClient.openSession(sessionId, settings) }
    return true
}
