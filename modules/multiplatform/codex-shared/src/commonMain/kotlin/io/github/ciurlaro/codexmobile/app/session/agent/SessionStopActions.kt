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


internal fun CodexSessionController.cancelTurnAction() {
    if (
        !state.value.isTurnActive || closed.load() ||
        !cancellationStarted.compareAndSet(false, true)
    ) {
        return
    }
    mutableState.update { it.copy(statusMessage = "Cancelling…") }
    if (turnStartCompleted.load()) state.value.sessionId?.let(::dispatchCancellation)
}

internal suspend fun CodexSessionController.stopAndCloseAction(reason: String, signOut: Boolean = false): Boolean {
    if (!closed.compareAndSet(false, true)) return false
    val before = state.value
    resetAuthenticationState()
    resetTurnState()
    mutableState.update {
        it.copy(
            statusMessage = reason,
            sessionId = null,
            isAuthenticated = false,
            signInUrl = null,
            isTurnActive = false,
            pendingApproval = null,
            pendingElicitation = null,
            workActivity = null,
            diagnosticCode = null,
        )
    }
    if (before.isTurnActive && before.sessionId != null) {
        withTimeoutOrNull(STOP_TIMEOUT_MILLIS) {
            runCatching { agentClient.cancelTurn(before.sessionId) }
        }
    }
    val signedOut = !signOut || withTimeoutOrNull(STOP_TIMEOUT_MILLIS) {
        runCatching { agentClient.signOut() }.isSuccess
    } == true
    agentClient.close()
    eventJob.cancel()
    mutableState.update {
        it.copy(
            statusMessage = if (signedOut) it.statusMessage else "ChatGPT sign-out failed; try again",
            terminal = true,
        )
    }
    return signedOut
}

internal fun CodexSessionController.closeAction() {
    if (!closed.compareAndSet(false, true)) return
    resetAuthenticationState()
    resetTurnState()
    mutableState.update {
        it.copy(
            statusMessage = "Background work ended",
            sessionId = null,
            isAuthenticated = false,
            signInUrl = null,
            isTurnActive = false,
            pendingApproval = null,
            pendingElicitation = null,
            workActivity = null,
            diagnosticCode = null,
            terminal = true,
        )
    }
    agentClient.close()
    eventJob.cancel()
}

internal fun CodexSessionController.dispatchCancellationAction(sessionId: SessionId) {
    if (!cancellationDispatched.compareAndSet(false, true)) return
    launchVisibleFailure(resetTurn = true, resetCancellation = true) {
        agentClient.cancelTurn(sessionId)
    }
}

internal fun CodexSessionController.resetTurnStateAction() {
    turnClaimed.store(false)
    turnStartCompleted.store(false)
    cancellationStarted.store(false)
    cancellationDispatched.store(false)
}

internal fun CodexSessionController.resetAuthenticationStateAction() {
    authenticationStarted.store(false)
}

internal fun CodexSessionController.launchVisibleFailureAction(
    resetAuthentication: Boolean = false,
    resetTurn: Boolean = false,
    resetCancellation: Boolean = false,
    block: suspend () -> Unit,
) {
    scope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (resetAuthentication) authenticationStarted.store(false)
            if (resetTurn) turnClaimed.store(false)
            if (resetCancellation) cancellationStarted.store(false)
            if (!closed.load()) {
                mutableState.update {
                    it.copy(
                        statusMessage = error.message?.take(MAX_VISIBLE_ERROR_CHARS) ?: "Codex failed",
                        signInUrl = null,
                        isTurnActive = false,
                        attentionRequired = true,
                        diagnosticCode = "client_request",
                    )
                }
            }
        }
    }
}
