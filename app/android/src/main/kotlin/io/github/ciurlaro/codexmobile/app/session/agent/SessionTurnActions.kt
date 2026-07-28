package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentElicitation
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentHookCatalog
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentSkillPackageCatalog
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.platform.android.AndroidSkillPackageManager
import io.github.ciurlaro.codexmobile.platform.android.AndroidPluginMarketplaceManager
import java.util.concurrent.atomic.AtomicBoolean
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
    val shouldStart = synchronized(lock) {
        if (closed.get() || authenticationStarted) false else true.also { authenticationStarted = true }
    }
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
    if (closed.get()) return
    mutableState.update { it.copy(statusMessage = "Cancelling sign-in…") }
    launchVisibleFailure(resetAuthentication = true) {
        agentClient.cancelAuthentication()
        synchronized(lock) { authenticationStarted = false }
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
        turnStartCompleted.set(true)
        if (cancellationStarted.get()) dispatchCancellation(sessionId)
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
        turnStartCompleted.set(true)
        if (cancellationStarted.get()) dispatchCancellation(sessionId)
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
    if (closed.get() || !turnClaimed.compareAndSet(false, true)) return false
    cancellationStarted.set(false)
    cancellationDispatched.set(false)
    turnStartCompleted.set(false)
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
    if (pending.requestId != requestId || closed.get()) return
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
    if (pending.requestId != requestId || closed.get()) return
    mutableState.update { it.copy(pendingElicitation = null, attentionRequired = false) }
    launchVisibleFailure { agentClient.resolveElicitation(requestId, response) }
}

internal fun CodexSessionController.startNewChatAction(): Boolean {
    if (!state.value.isAuthenticated || state.value.isTurnActive || closed.get()) return false
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
    if (!state.value.isAuthenticated || state.value.isTurnActive || closed.get()) return false
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
