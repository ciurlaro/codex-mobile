package io.github.ciurlaro.codexmobile.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.ToolRejectedException
import io.github.ciurlaro.codexmobile.core.ToolResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
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
    val scopeSelected: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as CodexMobileApplication).graph
    private val agentClient = graph.newAgentClient()
    private val mutableState = MutableStateFlow(
        MainUiState(scopeSelected = graph.platform.currentScopeId() != null),
    )
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
            } catch (error: CancellationException) {
                throw error
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

    fun selectScope(uri: Uri) {
        viewModelScope.launch {
            try {
                graph.platform.persistScope(uri)
                mutableState.update {
                    it.copy(status = "Read-only document folder selected", scopeSelected = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        status = "Document folder selection failed",
                        scopeSelected = graph.platform.currentScopeId() != null,
                    )
                }
            }
        }
    }

    fun scopeSelectionCancelled() {
        mutableState.update { it.copy(status = "Document folder selection cancelled") }
    }

    fun revokeScope() {
        val scopeId = graph.platform.currentScopeId() ?: return
        viewModelScope.launch {
            try {
                graph.platform.revokeScope(scopeId)
                mutableState.update {
                    it.copy(status = "Document folder access revoked", scopeSelected = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        status = "Document folder revocation failed",
                        scopeSelected = graph.platform.currentScopeId() != null,
                    )
                }
            }
        }
    }

    fun refreshScope() {
        mutableState.update {
            it.copy(scopeSelected = graph.platform.currentScopeId() != null)
        }
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

            is AgentEvent.ToolRequested -> executeTool(event)
        }
    }

    private suspend fun executeTool(event: AgentEvent.ToolRequested) {
        mutableState.update { it.copy(status = "Reading selected documents…") }
        val scopeId = graph.platform.currentScopeId()
        val result = if (scopeId == null) {
            ToolResult.Rejected(event.call.id, "No document folder is selected")
        } else {
            try {
                graph.toolExecutor.execute(graph.toolExecutor.prepare(event.call, scopeId))
            } catch (error: ToolRejectedException) {
                ToolResult.Rejected(event.call.id, error.message ?: "Document request was rejected")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ToolResult.Failed(event.call.id, "tool_failure", "Android document tool failed")
            }
        }
        try {
            agentClient.submitToolResult(event.sessionId, result)
            mutableState.update { it.copy(status = "Codex is responding…") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            showFailure(error)
        }
    }

    private fun openSessionOnce() {
        if (state.value.sessionId != null || !openingSession.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                agentClient.openSession()
            } catch (error: CancellationException) {
                throw error
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
            } catch (error: CancellationException) {
                throw error
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
