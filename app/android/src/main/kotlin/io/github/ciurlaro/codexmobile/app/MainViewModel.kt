package io.github.ciurlaro.codexmobile.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.ApprovalPreview
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.ToolEffect
import io.github.ciurlaro.codexmobile.core.ToolPlan
import io.github.ciurlaro.codexmobile.core.ToolRejectedException
import io.github.ciurlaro.codexmobile.core.ToolResult
import io.github.ciurlaro.codexmobile.core.UserApproval
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val status: String = "Ready to sign in",
    val streamedText: String = "",
    val sessionId: SessionId? = null,
    val verificationUrl: String? = null,
    val userCode: String? = null,
    val turnActive: Boolean = false,
    val scopeSelected: Boolean = false,
    val mutationScopeSelected: Boolean = false,
    val approvalPreview: ApprovalPreview? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as CodexMobileApplication).graph
    private val agentClient = graph.newAgentClient()
    private val mutableState = MutableStateFlow(
        MainUiState(
            scopeSelected = graph.platform.currentScopeId() != null,
            mutationScopeSelected = graph.platform.currentScopeAllowsMutations(),
        ),
    )
    private val openingSession = AtomicBoolean(false)
    private var pendingApproval: PendingApproval? = null
    private var approvalTimeout: Job? = null

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
                    it.copy(
                        status = "Read-only document folder selected",
                        scopeSelected = true,
                        mutationScopeSelected = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        status = "Document folder selection failed",
                        scopeSelected = graph.platform.currentScopeId() != null,
                        mutationScopeSelected = graph.platform.currentScopeAllowsMutations(),
                    )
                }
            }
        }
    }

    fun selectMutationScope(uri: Uri) {
        viewModelScope.launch {
            try {
                graph.platform.persistMutationScope(uri)
                mutableState.update {
                    it.copy(
                        status = "Disposable mutation folder selected",
                        scopeSelected = true,
                        mutationScopeSelected = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        status = "Disposable mutation folder selection failed",
                        scopeSelected = graph.platform.currentScopeId() != null,
                        mutationScopeSelected = graph.platform.currentScopeAllowsMutations(),
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
                    it.copy(
                        status = "Document folder access revoked",
                        scopeSelected = false,
                        mutationScopeSelected = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        status = "Document folder revocation failed",
                        scopeSelected = graph.platform.currentScopeId() != null,
                        mutationScopeSelected = graph.platform.currentScopeAllowsMutations(),
                    )
                }
            }
        }
    }

    fun refreshScope() {
        mutableState.update {
            it.copy(
                scopeSelected = graph.platform.currentScopeId() != null,
                mutationScopeSelected = graph.platform.currentScopeAllowsMutations(),
            )
        }
    }

    fun approveMutation() {
        val pending = takePendingApproval() ?: return
        mutableState.update { it.copy(status = "Applying approved Android change…") }
        viewModelScope.launch {
            executePrepared(pending.event, pending.plan, UserApproval.grant(pending.plan))
        }
    }

    fun denyMutation() {
        rejectPendingApproval("User denied the Android change")
    }

    override fun onCleared() {
        takePendingApproval()?.let { graph.toolExecutor.abandon(it.plan) }
        agentClient.close()
    }

    internal suspend fun reduce(event: AgentEvent) {
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

            is AgentEvent.Failure -> {
                abandonPendingApproval()
                mutableState.update {
                    it.copy(
                        status = event.message,
                        sessionId = if (event.sessionId == null) null else it.sessionId,
                        verificationUrl = null,
                        userCode = null,
                        turnActive = false,
                    )
                }
            }

            is AgentEvent.ToolRequested -> executeTool(event)
        }
    }

    private suspend fun executeTool(event: AgentEvent.ToolRequested) {
        mutableState.update { it.copy(status = "Resolving Android document request…") }
        val scopeId = graph.platform.currentScopeId()
        if (scopeId == null) {
            submitToolResult(event, ToolResult.Rejected(event.call.id, "No document folder is selected"))
            return
        }
        val plan = try {
            withContext(Dispatchers.IO) { graph.toolExecutor.prepare(event.call, scopeId) }
        } catch (error: ToolRejectedException) {
            submitToolResult(
                event,
                ToolResult.Rejected(event.call.id, error.message ?: "Document request was rejected"),
            )
            return
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            submitToolResult(
                event,
                ToolResult.Failed(event.call.id, "tool_failure", "Android document tool failed"),
            )
            return
        }

        if (plan.effect == ToolEffect.MUTATION) {
            requestApproval(event, plan)
        } else {
            executePrepared(event, plan)
        }
    }

    private suspend fun executePrepared(
        event: AgentEvent.ToolRequested,
        plan: ToolPlan,
        approval: UserApproval? = null,
    ) {
        val result = try {
            withContext(Dispatchers.IO) { graph.toolExecutor.execute(plan, approval) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ToolResult.Failed(event.call.id, "tool_failure", "Android document tool failed")
        }
        submitToolResult(event, result)
    }

    private suspend fun submitToolResult(event: AgentEvent.ToolRequested, result: ToolResult) {
        try {
            agentClient.submitToolResult(event.sessionId, result)
            mutableState.update { it.copy(status = "Codex is responding…") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            showFailure(error)
        }
    }

    private suspend fun requestApproval(event: AgentEvent.ToolRequested, plan: ToolPlan) {
        val preview = plan.approvalPreview
            ?: return executePrepared(event, plan)
        if (pendingApproval != null) {
            graph.toolExecutor.abandon(plan)
            submitToolResult(event, ToolResult.Rejected(event.call.id, "Another approval is already pending"))
            return
        }
        pendingApproval = PendingApproval(event, plan)
        mutableState.update {
            it.copy(status = "Waiting for explicit approval", approvalPreview = preview)
        }
        approvalTimeout?.cancel()
        approvalTimeout = viewModelScope.launch {
            delay(APPROVAL_TIMEOUT_MILLIS)
            rejectPendingApproval("Android change approval timed out")
        }
    }

    private fun rejectPendingApproval(reason: String) {
        val pending = takePendingApproval() ?: return
        mutableState.update { it.copy(status = reason) }
        graph.toolExecutor.abandon(pending.plan)
        viewModelScope.launch {
            submitToolResult(pending.event, ToolResult.Rejected(pending.event.call.id, reason))
        }
    }

    private fun takePendingApproval(): PendingApproval? {
        val pending = pendingApproval ?: return null
        pendingApproval = null
        approvalTimeout?.cancel()
        approvalTimeout = null
        mutableState.update { it.copy(approvalPreview = null) }
        return pending
    }

    private fun abandonPendingApproval() {
        val pending = takePendingApproval() ?: return
        graph.toolExecutor.abandon(pending.plan)
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

    private data class PendingApproval(
        val event: AgentEvent.ToolRequested,
        val plan: ToolPlan,
    )

    private companion object {
        const val APPROVAL_TIMEOUT_MILLIS = 30_000L
    }
}
