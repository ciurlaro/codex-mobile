package io.github.ciurlaro.codexmobile.app

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolResult
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
    val sessionId: SessionId? = null,
    val verificationUrl: String? = null,
    val userCode: String? = null,
    val turnActive: Boolean = false,
    val pendingTool: AgentEvent.ToolRequested? = null,
    val attentionRequired: Boolean = false,
    val diagnosticCode: String? = null,
    val terminal: Boolean = false,
)

internal class ForegroundSessionController(
    private val agentClient: AgentClient,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(ForegroundSessionState())
    private val openingSession = AtomicBoolean(false)
    private val turnClaimed = AtomicBoolean(false)
    private val cancellationStarted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private var authenticationStarted = false
    private var toolClaim: ToolClaim? = null
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
                verificationUrl = null,
                userCode = null,
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
                it.copy(status = "Ready to sign in", verificationUrl = null, userCode = null)
            }
        }
    }

    fun submit(prompt: String) {
        if (prompt.isBlank()) {
            mutableState.update { it.copy(status = "Enter a prompt first") }
            return
        }
        val sessionId = state.value.sessionId
        if (sessionId == null) {
            mutableState.update { it.copy(status = "Sign in and wait for a session first") }
            return
        }
        if (closed.get() || !turnClaimed.compareAndSet(false, true)) return
        cancellationStarted.set(false)

        mutableState.update {
            it.copy(
                status = "Codex is responding…",
                streamedText = "",
                turnActive = true,
                attentionRequired = false,
                diagnosticCode = null,
            )
        }
        launchVisibleFailure(resetTurn = true) { agentClient.sendPrompt(sessionId, prompt) }
    }

    fun cancelTurn() {
        val sessionId = state.value.sessionId ?: return
        if (
            !state.value.turnActive || closed.get() ||
            !cancellationStarted.compareAndSet(false, true)
        ) {
            return
        }
        mutableState.update { it.copy(status = "Cancelling…") }
        launchVisibleFailure(resetTurn = true, resetCancellation = true) {
            agentClient.cancelTurn(sessionId)
        }
    }

    fun claimTool(owner: String, callId: ToolCallId): AgentEvent.ToolRequested? = synchronized(lock) {
        val claim = toolClaim ?: return@synchronized null
        if (closed.get() || claim.event.call.id != callId || claim.owner != null) return@synchronized null
        claim.owner = owner
        claim.event
    }

    fun beginTool(owner: String, callId: ToolCallId): Boolean = synchronized(lock) {
        val claim = toolClaim ?: return@synchronized false
        if (
            closed.get() || claim.owner != owner || claim.event.call.id != callId || claim.dispatchStarted
        ) {
            return@synchronized false
        }
        claim.dispatchStarted = true
        true
    }

    fun submitToolResult(owner: String, event: AgentEvent.ToolRequested, result: ToolResult): Boolean {
        val accepted = synchronized(lock) {
            val claim = toolClaim
            if (
                closed.get() || claim?.owner != owner || !claim.dispatchStarted ||
                claim.event.sessionId != event.sessionId || claim.event.call.id != event.call.id
            ) {
                false
            } else {
                toolClaim = null
                true
            }
        }
        if (!accepted) return false
        mutableState.update { it.copy(pendingTool = null, status = "Codex is responding…") }
        launchVisibleFailure { agentClient.submitToolResult(event.sessionId, result) }
        return true
    }

    fun releaseOwner(owner: String, reason: String) {
        val claim = synchronized(lock) {
            toolClaim?.takeIf { it.owner == owner }?.also { toolClaim = null }
        } ?: return
        mutableState.update { it.copy(pendingTool = null) }
        val result = if (claim.dispatchStarted) {
            ToolResult.Failed(claim.event.call.id, "tool_interrupted", reason)
        } else {
            ToolResult.Rejected(claim.event.call.id, reason)
        }
        launchVisibleFailure { agentClient.submitToolResult(claim.event.sessionId, result) }
    }

    suspend fun stopAndClose(reason: String, signOut: Boolean = false): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        val before = state.value
        synchronized(lock) {
            authenticationStarted = false
            toolClaim = null
        }
        turnClaimed.set(false)
        cancellationStarted.set(false)
        mutableState.update {
            it.copy(
                status = reason,
                sessionId = null,
                verificationUrl = null,
                userCode = null,
                turnActive = false,
                pendingTool = null,
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
            toolClaim = null
        }
        turnClaimed.set(false)
        cancellationStarted.set(false)
        mutableState.update {
            it.copy(
                status = "Background work ended",
                sessionId = null,
                verificationUrl = null,
                userCode = null,
                turnActive = false,
                pendingTool = null,
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
                    verificationUrl = event.verificationUrl,
                    userCode = event.userCode,
                )
            }

            AgentEvent.Authenticated -> {
                synchronized(lock) { authenticationStarted = false }
                mutableState.update {
                    it.copy(
                        status = "Signed in; starting session…",
                        verificationUrl = null,
                        userCode = null,
                        diagnosticCode = null,
                    )
                }
                openSessionOnce()
            }

            is AgentEvent.SessionOpened -> mutableState.update {
                it.copy(status = "Ready", sessionId = event.sessionId, diagnosticCode = null)
            }

            is AgentEvent.TextDelta -> mutableState.update {
                if (it.sessionId != event.sessionId || it.streamedText.endsWith(TRUNCATION_MARKER)) {
                    it
                } else {
                    val remaining = MAX_STREAMED_TEXT_CHARS - it.streamedText.length
                    if (event.text.length <= remaining) {
                        it.copy(streamedText = it.streamedText + event.text)
                    } else {
                        it.copy(streamedText = it.streamedText + event.text.take(remaining) + TRUNCATION_MARKER)
                    }
                }
            }

            is AgentEvent.TurnCompleted -> {
                turnClaimed.set(false)
                cancellationStarted.set(false)
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
                cancellationStarted.set(false)
                synchronized(lock) {
                    authenticationStarted = false
                    toolClaim = null
                }
                mutableState.update {
                    it.copy(
                        status = event.message.take(MAX_VISIBLE_ERROR_CHARS),
                        sessionId = if (event.sessionId == null) null else it.sessionId,
                        verificationUrl = null,
                        userCode = null,
                        turnActive = false,
                        pendingTool = null,
                        attentionRequired = true,
                        diagnosticCode = event.code,
                    )
                }
            }

            is AgentEvent.ToolRequested -> receiveTool(event)
        }
    }

    private fun receiveTool(event: AgentEvent.ToolRequested) {
        val accepted = synchronized(lock) {
            if (toolClaim == null) {
                toolClaim = ToolClaim(event)
                true
            } else {
                false
            }
        }
        if (accepted) {
            mutableState.update { it.copy(status = "Open Codex Mobile to review Android access", pendingTool = event) }
        } else {
            launchVisibleFailure {
                agentClient.submitToolResult(
                    event.sessionId,
                    ToolResult.Rejected(event.call.id, "Another Android request is already active"),
                )
            }
        }
    }

    private fun openSessionOnce() {
        if (state.value.sessionId != null || !openingSession.compareAndSet(false, true)) return
        launchVisibleFailure {
            try {
                agentClient.openSession()
            } finally {
                openingSession.set(false)
            }
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
                            verificationUrl = null,
                            userCode = null,
                            turnActive = false,
                            attentionRequired = true,
                            diagnosticCode = "client_request",
                        )
                    }
                }
            }
        }
    }

    private data class ToolClaim(
        val event: AgentEvent.ToolRequested,
        var owner: String? = null,
        var dispatchStarted: Boolean = false,
    )

    private companion object {
        const val MAX_STREAMED_TEXT_CHARS = 256 * 1024
        const val MAX_VISIBLE_ERROR_CHARS = 500
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val TRUNCATION_MARKER = "\n[Response truncated]"
    }
}
