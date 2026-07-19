package io.github.ciurlaro.codexmobile.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.SessionId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val status: String = "Ready to sign in",
    val streamedText: String = "",
    val sessionId: SessionId? = null,
    val verificationUrl: String? = null,
    val userCode: String? = null,
    val turnActive: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as CodexMobileApplication).graph
    private val agentClient = graph.newAgentClient()
    private val mutableState = MutableStateFlow(MainUiState())
    private val openingSession = AtomicBoolean(false)

    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            agentClient.events.collect(::reduce)
        }
    }

    fun authenticate() {
        mutableState.update {
            it.copy(status = "Checking sign-in…", verificationUrl = null, userCode = null)
        }
        launchVisibleFailure { agentClient.authenticate() }
    }

    fun cancelAuthentication() {
        mutableState.update { it.copy(status = "Cancelling sign-in…") }
        viewModelScope.launch {
            try {
                agentClient.cancelAuthentication()
                mutableState.update {
                    it.copy(status = "Ready to sign in", verificationUrl = null, userCode = null)
                }
            } catch (error: Exception) {
                showFailure(error)
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
        if (state.value.turnActive) return

        mutableState.update {
            it.copy(status = "Codex is responding…", streamedText = "", turnActive = true)
        }
        launchVisibleFailure { agentClient.sendPrompt(sessionId, prompt) }
    }

    fun cancel() {
        val sessionId = state.value.sessionId ?: return
        mutableState.update { it.copy(status = "Cancelling…") }
        launchVisibleFailure { agentClient.cancelTurn(sessionId) }
    }

    override fun onCleared() {
        agentClient.close()
    }

    private suspend fun reduce(event: AgentEvent) {
        when (event) {
            is AgentEvent.AuthenticationRequired -> mutableState.update {
                it.copy(
                    status = "Finish sign-in in your browser",
                    verificationUrl = event.verificationUrl,
                    userCode = event.userCode,
                )
            }

            AgentEvent.Authenticated -> {
                mutableState.update {
                    it.copy(status = "Signed in; starting session…", verificationUrl = null, userCode = null)
                }
                openSessionOnce()
            }

            is AgentEvent.SessionOpened -> mutableState.update {
                it.copy(status = "Ready", sessionId = event.sessionId)
            }

            is AgentEvent.TextDelta -> mutableState.update {
                if (it.sessionId == event.sessionId) it.copy(streamedText = it.streamedText + event.text)
                else it
            }

            is AgentEvent.TurnCompleted -> mutableState.update {
                it.copy(status = "Ready", turnActive = false)
            }

            is AgentEvent.Failure -> mutableState.update {
                it.copy(
                    status = event.message,
                    sessionId = if (event.sessionId == null) null else it.sessionId,
                    verificationUrl = null,
                    userCode = null,
                    turnActive = false,
                )
            }

            is AgentEvent.ToolRequested -> mutableState.update {
                it.copy(status = "Device tools are disabled in Step 01", turnActive = false)
            }
        }
    }

    private fun openSessionOnce() {
        if (state.value.sessionId != null || !openingSession.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                agentClient.openSession()
            } catch (error: Exception) {
                showFailure(error)
            } finally {
                openingSession.set(false)
            }
        }
    }

    private fun launchVisibleFailure(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: Exception) {
                showFailure(error)
            }
        }
    }

    private fun showFailure(error: Exception) {
        mutableState.update {
            it.copy(
                status = error.message?.take(500) ?: "Codex failed",
                verificationUrl = null,
                userCode = null,
                turnActive = false,
            )
        }
    }
}
