package io.github.ciurlaro.codexmobile.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.ApprovalPreview
import io.github.ciurlaro.codexmobile.core.MutationRecordId
import io.github.ciurlaro.codexmobile.core.MutationState
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.ToolEffect
import io.github.ciurlaro.codexmobile.core.ToolPlan
import io.github.ciurlaro.codexmobile.core.ToolRejectedException
import io.github.ciurlaro.codexmobile.core.ToolResult
import io.github.ciurlaro.codexmobile.core.UserApproval
import java.util.UUID
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
    val recoveryNotices: List<MutationRecoveryNotice> = emptyList(),
    val backgroundActive: Boolean = false,
    val backgroundNotificationVisible: Boolean = true,
)

data class MutationRecoveryNotice(
    val recordId: MutationRecordId,
    val state: MutationState,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as CodexMobileApplication).graph
    private val appContext = application.applicationContext
    private val toolOwner = UUID.randomUUID().toString()
    private val mutableState = MutableStateFlow(
        MainUiState(
            scopeSelected = graph.platform.currentScopeId() != null,
            mutationScopeSelected = graph.platform.currentScopeAllowsMutations(),
        ),
    )
    private var pendingApproval: PendingApproval? = null
    private var approvalTimeout: Job? = null
    private var serviceController: ForegroundSessionController? = null
    private var serviceStateJob: Job? = null
    private var notificationsEnabled: (() -> Boolean)? = null
    private var bindingRequested = false
    internal var serviceInstanceId: String? = null
        private set

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as? CodexForegroundService.LocalBinder ?: return
            serviceController = binder.controller
            notificationsEnabled = binder::notificationsEnabled
            serviceInstanceId = binder.serviceInstanceId
            bindingRequested = true
            serviceStateJob?.cancel()
            serviceStateJob = viewModelScope.launch {
                binder.controller.state.collect { session ->
                    applySessionState(session, binder.notificationsEnabled())
                    session.pendingTool?.let { event ->
                        binder.controller.claimTool(toolOwner, event.call.id)?.let { claimed ->
                            launch { executeTool(claimed, binder.controller) }
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = serviceEnded()

        override fun onBindingDied(name: ComponentName) = serviceEnded()

        override fun onNullBinding(name: ComponentName) = serviceEnded()
    }

    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { refreshRecovery(reconcile = true) }
        viewModelScope.launch {
            graph.backgroundFailure.collect { failure ->
                failure?.let { message ->
                    mutableState.update {
                        it.copy(status = message, backgroundActive = false)
                    }
                }
            }
        }
        val backgroundWasActive = graph.wasBackgroundActive()
        bindService(flags = 0)
        if (backgroundWasActive) {
            viewModelScope.launch {
                delay(EXISTING_SERVICE_BIND_TIMEOUT_MILLIS)
                if (serviceController == null && graph.wasBackgroundActive()) {
                    if (bindingRequested) runCatching { appContext.unbindService(serviceConnection) }
                    bindingRequested = false
                    mutableState.update {
                        it.copy(status = "Previous background work ended unexpectedly; recovery was checked")
                    }
                    graph.markBackgroundActive(false)
                }
            }
        }
    }

    fun authenticate() {
        mutableState.update {
            it.copy(status = "Starting protected background work…", verificationUrl = null, userCode = null)
        }
        serviceController?.let {
            it.authenticate()
            return
        }
        val authorization = graph.authorizeForegroundStart()
        try {
            appContext.startForegroundService(
                CodexForegroundService.startIntent(appContext, authorization, authenticate = true),
            )
            bindService(Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            graph.revokeForegroundStart(authorization)
            mutableState.update {
                it.copy(
                    status = "Android could not start background work; keep Codex Mobile visible and try again",
                    backgroundActive = false,
                )
            }
        }
    }

    fun cancelAuthentication() {
        mutableState.update { it.copy(status = "Cancelling sign-in…") }
        serviceController?.cancelAuthentication()
            ?: mutableState.update { it.copy(status = "Ready to sign in") }
    }

    fun submit(prompt: String) {
        if (prompt.isBlank()) {
            mutableState.update { it.copy(status = "Enter a prompt first") }
            return
        }
        serviceController?.submit(prompt)
            ?: mutableState.update { it.copy(status = "Start a background session first") }
    }

    fun cancel() {
        serviceController?.cancelTurn()
    }

    fun stopBackgroundWork() {
        runCatching { appContext.startService(CodexForegroundService.stopIntent(appContext)) }
            .onFailure {
                mutableState.update { state -> state.copy(status = "Background work could not be stopped") }
            }
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
                refreshRecovery(reconcile = true)
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
                refreshRecovery(reconcile = true)
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
                backgroundNotificationVisible = serviceController?.let {
                    notificationsEnabled?.invoke() ?: false
                } ?: it.backgroundNotificationVisible,
            )
        }
    }

    fun approveMutation() {
        val pending = takePendingApproval() ?: return
        mutableState.update { it.copy(status = "Applying approved Android change…") }
        viewModelScope.launch {
            executePrepared(
                pending.event,
                pending.plan,
                UserApproval.grant(pending.plan),
                pending.controller,
            )
        }
    }

    fun denyMutation() {
        rejectPendingApproval("User denied the Android change")
    }

    override fun onCleared() {
        takePendingApproval()?.let { graph.toolExecutor.abandon(it.plan) }
        serviceController?.releaseOwner(toolOwner, "Android request UI was closed")
        serviceStateJob?.cancel()
        if (bindingRequested) runCatching { appContext.unbindService(serviceConnection) }
        bindingRequested = false
        serviceController = null
        notificationsEnabled = null
        serviceInstanceId = null
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
                    it.copy(status = "Signed in", verificationUrl = null, userCode = null)
                }
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

            is AgentEvent.ToolRequested -> executeTool(event, null)
        }
    }

    private suspend fun executeTool(
        event: AgentEvent.ToolRequested,
        controller: ForegroundSessionController?,
    ) {
        mutableState.update { it.copy(status = "Resolving Android document request…") }
        val scopeId = graph.platform.currentScopeId()
        if (scopeId == null) {
            finishTool(
                event,
                ToolResult.Rejected(event.call.id, "No document folder is selected"),
                controller,
            )
            return
        }
        val plan = try {
            withContext(Dispatchers.IO) { graph.toolExecutor.prepare(event.call, scopeId) }
        } catch (error: ToolRejectedException) {
            finishTool(
                event,
                ToolResult.Rejected(event.call.id, error.message ?: "Document request was rejected"),
                controller,
            )
            return
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            finishTool(
                event,
                ToolResult.Failed(event.call.id, "tool_failure", "Android document tool failed"),
                controller,
            )
            return
        }

        if (plan.effect == ToolEffect.MUTATION) {
            requestApproval(event, plan, controller)
        } else {
            executePrepared(event, plan, controller = controller)
        }
    }

    private suspend fun executePrepared(
        event: AgentEvent.ToolRequested,
        plan: ToolPlan,
        approval: UserApproval? = null,
        controller: ForegroundSessionController? = null,
    ) {
        if (controller != null && !controller.beginTool(toolOwner, event.call.id)) {
            graph.toolExecutor.abandon(plan)
            mutableState.update { it.copy(status = "Android request expired before execution") }
            return
        }
        val result = try {
            withContext(Dispatchers.IO) { graph.toolExecutor.execute(plan, approval) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ToolResult.Failed(event.call.id, "tool_failure", "Android document tool failed")
        }
        refreshRecovery(reconcile = false)
        submitToolResult(event, result, controller)
    }

    fun acknowledgeMutation(recordId: MutationRecordId) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.toolExecutor.acknowledgeMutation(recordId) }
                refreshRecovery(reconcile = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(status = "Mutation recovery acknowledgement failed") }
            }
        }
    }

    private fun finishTool(
        event: AgentEvent.ToolRequested,
        result: ToolResult,
        controller: ForegroundSessionController?,
    ) {
        if (controller != null && !controller.beginTool(toolOwner, event.call.id)) {
            mutableState.update { it.copy(status = "Android request expired before execution") }
            return
        }
        submitToolResult(event, result, controller)
    }

    private fun submitToolResult(
        event: AgentEvent.ToolRequested,
        result: ToolResult,
        controller: ForegroundSessionController?,
    ) {
        if (controller == null || controller.submitToolResult(toolOwner, event, result)) {
            mutableState.update { it.copy(status = "Codex is responding…") }
        } else {
            mutableState.update { it.copy(status = "Android request expired before its result was accepted") }
        }
    }

    private suspend fun requestApproval(
        event: AgentEvent.ToolRequested,
        plan: ToolPlan,
        controller: ForegroundSessionController?,
    ) {
        val preview = plan.approvalPreview
            ?: return executePrepared(event, plan, controller = controller)
        if (pendingApproval != null) {
            graph.toolExecutor.abandon(plan)
            finishTool(
                event,
                ToolResult.Rejected(event.call.id, "Another approval is already pending"),
                controller,
            )
            return
        }
        pendingApproval = PendingApproval(event, plan, controller)
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
        finishTool(
            pending.event,
            ToolResult.Rejected(pending.event.call.id, reason),
            pending.controller,
        )
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

    private suspend fun refreshRecovery(reconcile: Boolean) {
        try {
            val records = withContext(Dispatchers.IO) {
                if (reconcile) {
                    graph.toolExecutor.reconcileUnresolved().also {
                        graph.toolExecutor.pruneResolvedMutations(
                            System.currentTimeMillis() - RESOLVED_MUTATION_RETENTION_MILLIS,
                        )
                    }
                } else {
                    graph.toolExecutor.visibleMutationRecords()
                }
            }
            mutableState.update { current ->
                current.copy(
                    recoveryNotices = records.map {
                        MutationRecoveryNotice(it.id, it.state)
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.update { it.copy(status = "Mutation recovery is unavailable") }
        }
    }

    private fun applySessionState(
        session: ForegroundSessionState,
        notificationVisible: Boolean,
    ) {
        if (session.terminal) abandonPendingApproval()
        mutableState.update {
            it.copy(
                status = session.status,
                streamedText = session.streamedText,
                sessionId = session.sessionId,
                verificationUrl = session.verificationUrl,
                userCode = session.userCode,
                turnActive = session.turnActive,
                backgroundActive = !session.terminal,
                backgroundNotificationVisible = notificationVisible,
            )
        }
    }

    private fun bindService(flags: Int): Boolean {
        if (bindingRequested) return true
        val bound = runCatching {
            appContext.bindService(
                CodexForegroundService.bindIntent(appContext),
                serviceConnection,
                flags,
            )
        }.getOrDefault(false)
        bindingRequested = bound
        return bound
    }

    private fun serviceEnded() {
        serviceStateJob?.cancel()
        serviceStateJob = null
        serviceController = null
        notificationsEnabled = null
        serviceInstanceId = null
        bindingRequested = false
        abandonPendingApproval()
        mutableState.update {
            it.copy(
                status = if (it.backgroundActive) "Background work ended; start again to continue" else it.status,
                sessionId = null,
                verificationUrl = null,
                userCode = null,
                turnActive = false,
                backgroundActive = false,
            )
        }
    }

    private data class PendingApproval(
        val event: AgentEvent.ToolRequested,
        val plan: ToolPlan,
        val controller: ForegroundSessionController?,
    )

    private companion object {
        const val APPROVAL_TIMEOUT_MILLIS = 30_000L
        const val EXISTING_SERVICE_BIND_TIMEOUT_MILLIS = 1_000L
        const val RESOLVED_MUTATION_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}
