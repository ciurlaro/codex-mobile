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


internal fun CodexSessionController.cancelTurnAction() {
    if (
        !state.value.isTurnActive || closed.get() ||
        !cancellationStarted.compareAndSet(false, true)
    ) {
        return
    }
    mutableState.update { it.copy(statusMessage = "Cancelling…") }
    if (turnStartCompleted.get()) state.value.sessionId?.let(::dispatchCancellation)
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
    turnClaimed.set(false)
    turnStartCompleted.set(false)
    cancellationStarted.set(false)
    cancellationDispatched.set(false)
}

internal fun CodexSessionController.resetAuthenticationStateAction() {
    synchronized(lock) { authenticationStarted = false }
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
            if (resetAuthentication) synchronized(lock) { authenticationStarted = false }
            if (resetTurn) turnClaimed.set(false)
            if (resetCancellation) cancellationStarted.set(false)
            if (!closed.get()) {
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
