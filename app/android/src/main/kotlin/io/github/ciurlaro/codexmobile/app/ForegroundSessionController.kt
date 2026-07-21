package io.github.ciurlaro.codexmobile.app

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
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
import kotlinx.coroutines.withTimeoutOrNull

internal data class ForegroundSessionState(
    val status: String = "Starting background session…",
    val streamedText: String = "",
    val shellExitCode: Int? = null,
    val sessionId: SessionId? = null,
    val authenticated: Boolean = false,
    val activeModel: String? = null,
    val activeEffort: String? = null,
    val activeServiceTier: String? = null,
    val signInUrl: String? = null,
    val turnActive: Boolean = false,
    val pendingApproval: AgentEvent.ApprovalRequested? = null,
    val workActivity: AgentWorkActivity? = null,
    val attentionRequired: Boolean = false,
    val diagnosticCode: String? = null,
    val terminal: Boolean = false,
)

internal class ForegroundSessionController(
    private val agentClient: AgentClient,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(ForegroundSessionState())
    private val turnClaimed = AtomicBoolean(false)
    private val turnStartCompleted = AtomicBoolean(false)
    private val cancellationStarted = AtomicBoolean(false)
    private val cancellationDispatched = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private var authenticationStarted = false
    private var eventJob: Job = scope.launch { agentClient.events.collect(::reduce) }

    val state: StateFlow<ForegroundSessionState> = mutableState.asStateFlow()

    fun authenticate() {
        val shouldStart = synchronized(lock) {
            if (closed.get() || authenticationStarted) false else true.also { authenticationStarted = true }
        }
        if (!shouldStart) return
        mutableState.update {
            it.copy(
                status = "Checking sign-in…",
                signInUrl = null,
                attentionRequired = false,
                diagnosticCode = null,
            )
        }
        launchVisibleFailure(resetAuthentication = true) { agentClient.authenticate() }
    }

    fun cancelAuthentication() {
        if (closed.get()) return
        mutableState.update { it.copy(status = "Cancelling sign-in…") }
        launchVisibleFailure(resetAuthentication = true) {
            agentClient.cancelAuthentication()
            synchronized(lock) { authenticationStarted = false }
            mutableState.update {
                it.copy(status = "Ready to sign in", signInUrl = null)
            }
        }
    }

    fun submit(prompt: String): Boolean = submit(AgentTurnRequest(prompt = prompt))

    fun submit(request: AgentTurnRequest): Boolean {
        if (request.prompt.isBlank() && request.capabilities.isEmpty()) {
            mutableState.update { it.copy(status = "Enter a prompt first") }
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

    fun submitShell(command: String, settings: AgentRuntimeSettings): Boolean {
        if (command.isBlank()) {
            mutableState.update { it.copy(status = "Enter a shell command after !") }
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

    private fun beginTurn(status: String): Boolean {
        if (!state.value.authenticated) {
            mutableState.update { it.copy(status = "Sign in before sending a message") }
            return false
        }
        if (closed.get() || !turnClaimed.compareAndSet(false, true)) return false
        cancellationStarted.set(false)
        cancellationDispatched.set(false)
        turnStartCompleted.set(false)
        mutableState.update {
            it.copy(
                status = status,
                streamedText = "",
                shellExitCode = null,
                turnActive = true,
                attentionRequired = false,
                diagnosticCode = null,
            )
        }
        return true
    }

    fun resolveApproval(requestId: String, accept: Boolean) {
        val pending = state.value.pendingApproval ?: return
        if (pending.requestId != requestId || closed.get()) return
        mutableState.update {
            it.copy(
                status = if (accept) "Continuing…" else "Declining…",
                pendingApproval = null,
                attentionRequired = false,
            )
        }
        launchVisibleFailure { agentClient.resolveApproval(requestId, accept) }
    }

    fun freshChat(): Boolean {
        if (!state.value.authenticated || state.value.turnActive || closed.get()) return false
        mutableState.update {
            it.copy(
                status = "Ready",
                streamedText = "",
                sessionId = null,
                diagnosticCode = null,
                attentionRequired = false,
            )
        }
        return true
    }

    fun openConversation(
        sessionId: SessionId,
        settings: AgentRuntimeSettings = AgentRuntimeSettings(),
    ): Boolean {
        if (!state.value.authenticated || state.value.turnActive || closed.get()) return false
        mutableState.update {
            it.copy(status = "Loading conversation…", streamedText = "", diagnosticCode = null)
        }
        launchVisibleFailure { agentClient.openSession(sessionId, settings) }
        return true
    }

    suspend fun listModels(): List<AgentModel> = agentClient.listModels()

    suspend fun listConversations(): List<AgentConversationSummary> = agentClient.listSessions()

    suspend fun readConversation(sessionId: SessionId): AgentConversation =
        agentClient.readSession(sessionId)

    suspend fun renameConversation(sessionId: SessionId, title: String) =
        agentClient.renameSession(sessionId, title)

    suspend fun deleteConversation(sessionId: SessionId) =
        agentClient.deleteSession(sessionId)

    fun cancelTurn() {
        if (
            !state.value.turnActive || closed.get() ||
            !cancellationStarted.compareAndSet(false, true)
        ) {
            return
        }
        mutableState.update { it.copy(status = "Cancelling…") }
        if (turnStartCompleted.get()) state.value.sessionId?.let(::dispatchCancellation)
    }

    suspend fun stopAndClose(reason: String, signOut: Boolean = false): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        val before = state.value
        synchronized(lock) {
            authenticationStarted = false
        }
        turnClaimed.set(false)
        turnStartCompleted.set(false)
        cancellationStarted.set(false)
        cancellationDispatched.set(false)
        mutableState.update {
            it.copy(
                status = reason,
                sessionId = null,
                authenticated = false,
                signInUrl = null,
                turnActive = false,
                pendingApproval = null,
                workActivity = null,
                diagnosticCode = null,
            )
        }
        if (before.turnActive && before.sessionId != null) {
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
                status = if (signedOut) it.status else "ChatGPT sign-out failed; try again",
                terminal = true,
            )
        }
        return signedOut
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            authenticationStarted = false
        }
        turnClaimed.set(false)
        turnStartCompleted.set(false)
        cancellationStarted.set(false)
        cancellationDispatched.set(false)
        mutableState.update {
            it.copy(
                status = "Background work ended",
                sessionId = null,
                authenticated = false,
                signInUrl = null,
                turnActive = false,
                pendingApproval = null,
                workActivity = null,
                diagnosticCode = null,
                terminal = true,
            )
        }
        agentClient.close()
        eventJob.cancel()
    }

    private fun reduce(event: AgentEvent) {
        if (closed.get()) return
        when (event) {
            is AgentEvent.AuthenticationRequired -> mutableState.update {
                it.copy(
                    status = "Finish sign-in in your browser",
                    signInUrl = event.signInUrl,
                )
            }

            AgentEvent.Authenticated -> {
                synchronized(lock) { authenticationStarted = false }
                mutableState.update {
                    it.copy(
                        status = "Ready",
                        authenticated = true,
                        signInUrl = null,
                        diagnosticCode = null,
                    )
                }
            }

            is AgentEvent.SessionOpened -> mutableState.update {
                it.copy(
                    status = "Ready",
                    sessionId = event.sessionId,
                    authenticated = true,
                    activeModel = event.model ?: it.activeModel,
                    activeEffort = event.effort ?: it.activeEffort,
                    activeServiceTier = event.serviceTier ?: it.activeServiceTier,
                    diagnosticCode = null,
                )
            }

            is AgentEvent.TextDelta -> appendStreamedText(event.sessionId, event.text)

            is AgentEvent.ShellOutputDelta -> appendStreamedText(event.sessionId, event.text)

            is AgentEvent.ShellCommandCompleted -> mutableState.update {
                if (it.sessionId == event.sessionId) it.copy(shellExitCode = event.exitCode) else it
            }

            is AgentEvent.TurnCompleted -> {
                turnClaimed.set(false)
                turnStartCompleted.set(false)
                cancellationStarted.set(false)
                cancellationDispatched.set(false)
                mutableState.update {
                    if (it.sessionId == event.sessionId) {
                        it.copy(status = "Ready", turnActive = false, diagnosticCode = null)
                    } else {
                        it
                    }
                }
            }

            is AgentEvent.Failure -> {
                turnClaimed.set(false)
                turnStartCompleted.set(false)
                cancellationStarted.set(false)
                cancellationDispatched.set(false)
                synchronized(lock) {
                    authenticationStarted = false
                }
                mutableState.update {
                    it.copy(
                        status = event.message.take(MAX_VISIBLE_ERROR_CHARS),
                        sessionId = if (event.sessionId == null) null else it.sessionId,
                        signInUrl = null,
                        turnActive = false,
                        pendingApproval = null,
                        workActivity = null,
                        attentionRequired = true,
                        diagnosticCode = event.code,
                    )
                }
            }

            is AgentEvent.ApprovalRequested -> {
                if (mutableState.value.pendingApproval == null) {
                    mutableState.update {
                        it.copy(
                            status = "Approval needed",
                            pendingApproval = event,
                            attentionRequired = true,
                        )
                    }
                } else {
                    launchVisibleFailure { agentClient.resolveApproval(event.requestId, false) }
                }
            }

            is AgentEvent.WorkActivityChanged -> mutableState.update {
                if (it.sessionId == event.sessionId) it.copy(workActivity = event.activity) else it
            }
        }
    }

    private fun appendStreamedText(sessionId: SessionId, text: String) {
        mutableState.update {
            if (it.sessionId != sessionId || it.streamedText.endsWith(TRUNCATION_MARKER)) {
                it
            } else {
                val remaining = MAX_STREAMED_TEXT_CHARS - it.streamedText.length
                if (text.length <= remaining) {
                    it.copy(streamedText = it.streamedText + text)
                } else {
                    it.copy(streamedText = it.streamedText + text.take(remaining) + TRUNCATION_MARKER)
                }
            }
        }
    }

    private fun dispatchCancellation(sessionId: SessionId) {
        if (!cancellationDispatched.compareAndSet(false, true)) return
        launchVisibleFailure(resetTurn = true, resetCancellation = true) {
            agentClient.cancelTurn(sessionId)
        }
    }

    private fun launchVisibleFailure(
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
                            status = error.message?.take(MAX_VISIBLE_ERROR_CHARS) ?: "Codex failed",
                            signInUrl = null,
                            turnActive = false,
                            attentionRequired = true,
                            diagnosticCode = "client_request",
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_STREAMED_TEXT_CHARS = 256 * 1024
        const val MAX_VISIBLE_ERROR_CHARS = 500
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val TRUNCATION_MARKER = "\n[Response truncated]"
    }
}
