package io.github.ciurlaro.codexmobile.app

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
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
    val authenticated: Boolean = false,
    val conversations: List<AgentConversationSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val models: List<AgentModel> = emptyList(),
    val draft: String = "",
    val selectedCapabilities: Set<AgentCapability> = emptySet(),
    val selectedModel: String? = null,
    val selectedEffort: String? = null,
    val drawerOpen: Boolean = false,
    val destination: AppDestination = AppDestination.CHAT,
    val popup: ChatPopup = ChatPopup.NONE,
    val historySearch: String = "",
    val conversationLoading: Boolean = false,
    val signInUrl: String? = null,
    val turnActive: Boolean = false,
    val scopeSelected: Boolean = false,
    val mutationScopeSelected: Boolean = false,
    val exportScopeSelected: Boolean = false,
    val approvalPreview: ApprovalPreview? = null,
    val recoveryNotices: List<MutationRecoveryNotice> = emptyList(),
    val backgroundActive: Boolean = false,
    val backgroundNotificationVisible: Boolean = true,
    val diagnosticCode: String? = null,
)

data class MutationRecoveryNotice(
    val recordId: MutationRecordId,
    val state: MutationState,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as CodexMobileApplication).graph
    private val appContext = application.applicationContext
    private val chatPreferences = appContext.getSharedPreferences(CHAT_PREFERENCES, Context.MODE_PRIVATE)
    private val toolOwner = UUID.randomUUID().toString()
    private val mutableState = MutableStateFlow(
        MainUiState(
            scopeSelected = graph.platform.currentScopeId() != null,
            mutationScopeSelected = graph.platform.currentScopeAllowsMutations(),
            exportScopeSelected = graph.platform.currentExportScopeId() != null,
            selectedModel = chatPreferences.getString(LAST_MODEL, null),
            selectedEffort = chatPreferences.getString(LAST_EFFORT, null),
        ),
    )
    private var pendingApproval: PendingApproval? = null
    private var approvalTimeout: Job? = null
    private var serviceController: ForegroundSessionController? = null
    private var serviceStateJob: Job? = null
    private var notificationsEnabled: (() -> Boolean)? = null
    private var signOutAction: (() -> Unit)? = null
    private var signOutPending = false
    private var bindingRequested = false
    private var chatDataRequested = false
    private var activeAssistantMessageId: String? = null
    private var pendingConversationId: SessionId? = null
    private var selectionRestoredSessionId: SessionId? = null
    internal var serviceInstanceId: String? = null
        private set

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as? CodexForegroundService.LocalBinder ?: return
            serviceController = binder.controller
            notificationsEnabled = binder::notificationsEnabled
            signOutAction = binder::signOut
            serviceInstanceId = binder.serviceInstanceId
            bindingRequested = true
            if (signOutPending) {
                signOutPending = false
                binder.signOut()
            }
            serviceStateJob?.cancel()
            serviceStateJob = viewModelScope.launch {
                binder.controller.state.collect { session ->
                    applySessionState(session, binder.notificationsEnabled())
                    if (session.authenticated && !chatDataRequested) {
                        chatDataRequested = true
                        launch { refreshChatData(binder.controller) }
                    }
                    if (session.terminal) releaseServiceBinding()
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
            it.copy(status = "Starting protected background work…", signInUrl = null)
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

    fun browserUnavailable() {
        mutableState.update { it.copy(status = "No browser can open the ChatGPT sign-in page") }
    }

    fun submit(prompt: String) {
        updateDraft(prompt)
        sendMessage()
    }

    fun sendMessage() {
        val before = mutableState.value
        if (before.draft.isBlank() && before.selectedCapabilities.isEmpty()) {
            mutableState.update { it.copy(status = "Enter a message or add a prompt tag") }
            return
        }
        val controller = serviceController
        if (controller == null) {
            mutableState.update { it.copy(status = "Start a background session first") }
            return
        }
        val clientMessageId = UUID.randomUUID().toString()
        val request = AgentTurnRequest(
            prompt = before.draft.trim(),
            clientMessageId = clientMessageId,
            model = before.selectedModel,
            effort = before.selectedEffort,
            capabilities = before.selectedCapabilities,
        )
        if (!controller.submit(request)) return

        val assistantId = "stream-$clientMessageId"
        activeAssistantMessageId = assistantId
        mutableState.update {
            it.copy(
                status = "Thinking",
                messages = it.messages + listOf(
                    ChatMessage(
                        id = "user-$clientMessageId",
                        role = AgentMessageRole.USER,
                        text = request.prompt,
                        capabilities = request.capabilities,
                        model = request.model,
                        effort = request.effort,
                    ),
                    ChatMessage(
                        id = assistantId,
                        role = AgentMessageRole.CODEX,
                        text = "",
                        streaming = true,
                    ),
                ),
                draft = "",
                selectedCapabilities = emptySet(),
                popup = ChatPopup.NONE,
            )
        }
    }

    fun updateDraft(value: String) {
        mutableState.update {
            it.copy(
                draft = value,
                popup = if (selectedTagQuery(value) != null) ChatPopup.TAGS else it.popup,
            )
        }
    }

    fun openHistory() {
        mutableState.update { it.copy(drawerOpen = true, popup = ChatPopup.NONE) }
        refreshConversations()
    }

    fun closeHistory() {
        mutableState.update { it.copy(drawerOpen = false, historySearch = "") }
    }

    fun updateHistorySearch(value: String) {
        mutableState.update { it.copy(historySearch = value) }
    }

    fun freshChat() {
        serviceController?.let { if (!it.freshChat()) return }
        pendingConversationId = null
        selectionRestoredSessionId = null
        activeAssistantMessageId = null
        mutableState.update {
            it.copy(
                status = if (it.authenticated) "Ready" else it.status,
                streamedText = "",
                sessionId = null,
                messages = emptyList(),
                draft = "",
                selectedCapabilities = emptySet(),
                drawerOpen = false,
                destination = AppDestination.CHAT,
                popup = ChatPopup.NONE,
                historySearch = "",
                conversationLoading = false,
            )
        }
    }

    fun selectConversation(sessionId: SessionId) {
        val controller = serviceController ?: return
        if (!controller.openConversation(sessionId)) return
        pendingConversationId = sessionId
        selectionRestoredSessionId = null
        activeAssistantMessageId = null
        mutableState.update {
            it.copy(
                sessionId = sessionId,
                messages = emptyList(),
                drawerOpen = false,
                popup = ChatPopup.NONE,
                historySearch = "",
                conversationLoading = true,
            )
        }
        viewModelScope.launch {
            try {
                val conversation = controller.readConversation(sessionId)
                if (pendingConversationId == sessionId) {
                    mutableState.update {
                        it.copy(
                            messages = conversation.messages.map { message -> message.toChatMessage() },
                            conversationLoading = false,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (pendingConversationId == sessionId) {
                    mutableState.update {
                        it.copy(status = "Conversation history could not be loaded", conversationLoading = false)
                    }
                }
            }
        }
    }

    fun openSettings() {
        mutableState.update {
            it.copy(destination = AppDestination.SETTINGS, drawerOpen = false, popup = ChatPopup.NONE)
        }
    }

    fun closeSettings() {
        mutableState.update { it.copy(destination = AppDestination.CHAT, popup = ChatPopup.NONE) }
    }

    fun showEffortSelector() {
        mutableState.update { it.copy(popup = ChatPopup.EFFORT) }
    }

    fun showModelSelector() {
        mutableState.update { it.copy(popup = ChatPopup.MODEL) }
    }

    fun showTagPicker() {
        mutableState.update { it.copy(popup = ChatPopup.TAGS) }
    }

    fun dismissPopup() {
        mutableState.update { it.copy(popup = ChatPopup.NONE) }
    }

    fun selectModel(modelId: String) {
        val model = mutableState.value.models.firstOrNull { it.id == modelId } ?: return
        mutableState.update {
            val effort = it.selectedEffort?.takeIf(model.supportedEfforts::contains)
                ?: model.defaultEffort
            it.copy(selectedModel = model.id, selectedEffort = effort, popup = ChatPopup.EFFORT)
        }
        persistSelection()
    }

    fun selectEffort(effort: String) {
        val current = mutableState.value
        val model = current.models.firstOrNull { it.id == current.selectedModel } ?: return
        if (effort !in model.supportedEfforts) return
        mutableState.update { it.copy(selectedEffort = effort, popup = ChatPopup.NONE) }
        persistSelection()
    }

    fun addCapability(capability: AgentCapability) {
        mutableState.update {
            it.copy(
                selectedCapabilities = it.selectedCapabilities + capability,
                draft = removeSelectedTagQuery(it.draft),
                popup = ChatPopup.NONE,
            )
        }
    }

    fun removeCapability(capability: AgentCapability) {
        mutableState.update { it.copy(selectedCapabilities = it.selectedCapabilities - capability) }
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

    fun signOut() {
        mutableState.update {
            it.copy(status = "Signing out…", signInUrl = null)
        }
        signOutAction?.invoke() ?: run {
            signOutPending = true
            if (!bindService(Context.BIND_AUTO_CREATE)) {
                signOutPending = false
                mutableState.update { it.copy(status = "ChatGPT sign-out could not start; try again") }
            }
        }
    }

    fun eraseAppData() {
        mutableState.update { it.copy(status = "Erasing Codex Mobile data…") }
        val accepted = appContext.getSystemService(ActivityManager::class.java)
            .clearApplicationUserData()
        if (!accepted) {
            mutableState.update { it.copy(status = "Android could not erase app data; try again") }
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

    fun selectExportScope(uri: Uri) {
        viewModelScope.launch {
            try {
                graph.platform.persistExportScope(uri)
                mutableState.update {
                    it.copy(status = "Export folder selected", exportScopeSelected = true)
                }
                refreshRecovery(reconcile = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        status = "Export folder selection failed",
                        exportScopeSelected = graph.platform.currentExportScopeId() != null,
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

    fun revokeExportScope() {
        val scopeId = graph.platform.currentExportScopeId() ?: return
        viewModelScope.launch {
            try {
                graph.platform.revokeExportScope(scopeId)
                mutableState.update {
                    it.copy(status = "Export folder access revoked", exportScopeSelected = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        status = "Export folder revocation failed",
                        exportScopeSelected = graph.platform.currentExportScopeId() != null,
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
                exportScopeSelected = graph.platform.currentExportScopeId() != null,
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
        signOutAction = null
        signOutPending = false
        serviceInstanceId = null
    }

    internal suspend fun reduce(event: AgentEvent) {
        when (event) {
            is AgentEvent.AuthenticationRequired -> mutableState.update {
                it.copy(
                    status = "Finish sign-in in your browser",
                    signInUrl = event.signInUrl,
                )
            }

            AgentEvent.Authenticated -> {
                mutableState.update {
                    it.copy(
                        status = "Signed in",
                        authenticated = true,
                        signInUrl = null,
                    )
                }
            }

            is AgentEvent.SessionOpened -> mutableState.update {
                it.copy(
                    status = "Ready",
                    authenticated = true,
                    sessionId = event.sessionId,
                    selectedModel = event.model ?: it.selectedModel,
                    selectedEffort = event.effort ?: it.selectedEffort,
                )
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
                        signInUrl = null,
                        turnActive = false,
                        diagnosticCode = event.code,
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
        val scopeId = graph.platform.scopeIdForTool(event.call.name)
        if (scopeId == null) {
            finishTool(
                event,
                ToolResult.Rejected(event.call.id, "No matching document workspace or folder is available"),
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

    private suspend fun refreshChatData(controller: ForegroundSessionController) {
        val models = runCatching { controller.listModels() }.getOrDefault(emptyList())
        val conversations = runCatching { controller.listConversations() }.getOrDefault(emptyList())
        mutableState.update { current ->
            val selected = models.firstOrNull { it.id == current.selectedModel }
                ?: models.firstOrNull(AgentModel::isDefault)
                ?: models.firstOrNull()
            val effort = selected?.let { model ->
                current.selectedEffort?.takeIf(model.supportedEfforts::contains)
                    ?: model.defaultEffort
            }
            current.copy(
                models = models,
                conversations = conversations,
                selectedModel = selected?.id ?: current.selectedModel,
                selectedEffort = effort ?: current.selectedEffort,
            )
        }
        persistSelection()
    }

    private fun refreshConversations() {
        val controller = serviceController ?: return
        viewModelScope.launch {
            try {
                val conversations = controller.listConversations()
                mutableState.update { it.copy(conversations = conversations) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Existing history remains usable when a refresh fails.
            }
        }
    }

    private fun persistSelection() {
        val current = mutableState.value
        chatPreferences.edit()
            .apply {
                if (current.selectedModel == null) remove(LAST_MODEL)
                else putString(LAST_MODEL, current.selectedModel)
                if (current.selectedEffort == null) remove(LAST_EFFORT)
                else putString(LAST_EFFORT, current.selectedEffort)
            }
            .apply()
    }

    private fun applySessionState(
        session: ForegroundSessionState,
        notificationVisible: Boolean,
    ) {
        if (session.terminal) abandonPendingApproval()
        val before = mutableState.value
        val finishedTurn = before.turnActive && !session.turnActive
        val assistantId = activeAssistantMessageId
        val restoreSelection = session.sessionId != null &&
            pendingConversationId == session.sessionId &&
            selectionRestoredSessionId != session.sessionId
        if (restoreSelection) selectionRestoredSessionId = session.sessionId
        mutableState.update { current ->
            var messages = current.messages
            if (assistantId != null) {
                messages = messages.map { message ->
                    if (message.id == assistantId) {
                        message.copy(text = session.streamedText, streaming = session.turnActive)
                    } else {
                        message
                    }
                }
                if (finishedTurn) messages = messages.filterNot { it.id == assistantId && it.text.isEmpty() }
            }
            current.copy(
                status = session.status,
                streamedText = session.streamedText,
                sessionId = session.sessionId,
                authenticated = session.authenticated,
                messages = messages,
                selectedModel = if (restoreSelection) session.activeModel ?: current.selectedModel
                else current.selectedModel,
                selectedEffort = if (restoreSelection) session.activeEffort ?: current.selectedEffort
                else current.selectedEffort,
                signInUrl = session.signInUrl,
                turnActive = session.turnActive,
                backgroundActive = !session.terminal,
                backgroundNotificationVisible = notificationVisible,
                diagnosticCode = session.diagnosticCode,
            )
        }
        if (finishedTurn) {
            activeAssistantMessageId = null
            refreshConversations()
        }
        if (restoreSelection) persistSelection()
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

    private fun releaseServiceBinding() {
        if (bindingRequested) runCatching { appContext.unbindService(serviceConnection) }
        serviceEnded()
    }

    private fun serviceEnded() {
        serviceStateJob?.cancel()
        serviceStateJob = null
        serviceController = null
        notificationsEnabled = null
        signOutAction = null
        signOutPending = false
        serviceInstanceId = null
        bindingRequested = false
        chatDataRequested = false
        abandonPendingApproval()
        mutableState.update {
            it.copy(
                status = if (it.backgroundActive) "Background work ended; start again to continue" else it.status,
                sessionId = null,
                authenticated = false,
                signInUrl = null,
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
        const val CHAT_PREFERENCES = "chat-ui"
        const val LAST_MODEL = "last-model"
        const val LAST_EFFORT = "last-effort"
        const val APPROVAL_TIMEOUT_MILLIS = 30_000L
        const val EXISTING_SERVICE_BIND_TIMEOUT_MILLIS = 1_000L
        const val RESOLVED_MUTATION_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}
