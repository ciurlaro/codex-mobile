package io.github.ciurlaro.codexmobile.app

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.platform.android.TelegramAuthEvent
import io.github.ciurlaro.codexmobile.platform.android.TelegramAuthPrompt
import io.github.ciurlaro.codexmobile.platform.android.TelegramAuthSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val authenticated: Boolean = false,
    val conversations: List<AgentConversationSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val models: List<AgentModel> = emptyList(),
    val draft: String = "",
    val selectedCapabilities: Set<AgentCapability> = emptySet(),
    val selectedModel: String? = null,
    val selectedEffort: String? = null,
    val selectedServiceTier: String? = null,
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.NEVER,
    val drawerOpen: Boolean = false,
    val destination: AppDestination = AppDestination.CHAT,
    val popup: ChatPopup = ChatPopup.NONE,
    val historySearch: String = "",
    val conversationLoading: Boolean = false,
    val signInUrl: String? = null,
    val turnActive: Boolean = false,
    val storagePermissionGranted: Boolean = false,
    val workspacePath: String? = null,
    val codexApproval: AgentEvent.ApprovalRequested? = null,
    val backgroundActive: Boolean = false,
    val backgroundNotificationVisible: Boolean = true,
    val diagnosticCode: String? = null,
    val telegramAvailable: Boolean = false,
    val telegramConnected: Boolean = false,
    val telegramUsername: String? = null,
    val telegramAuthPrompt: TelegramAuthPrompt? = null,
    val telegramBusy: Boolean = false,
    val telegramError: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as CodexMobileApplication).graph
    private val appContext = application.applicationContext
    private val chatPreferences = appContext.getSharedPreferences(CHAT_PREFERENCES, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        MainUiState(
            storagePermissionGranted = graph.platform.hasStoragePermission(),
            workspacePath = graph.platform.currentWorkspacePath(),
            selectedModel = chatPreferences.getString(LAST_MODEL, null),
            selectedEffort = chatPreferences.getString(LAST_EFFORT, null),
            selectedServiceTier = chatPreferences.getString(LAST_SPEED, null),
            approvalPreset = chatPreferences.getString(APPROVAL_POLICY, null)
                ?.let { saved -> AgentApprovalPreset.entries.firstOrNull { it.name == saved } }
                ?: AgentApprovalPreset.NEVER,
            telegramAvailable = graph.platform.telegramAvailable(),
        ),
    )
    private var serviceController: ForegroundSessionController? = null
    private var serviceStateJob: Job? = null
    private var notificationsEnabled: (() -> Boolean)? = null
    private var signOutAction: (() -> Unit)? = null
    private var signOutPending = false
    private var bindingRequested = false
    private var chatDataRequested = false
    private var activeAssistantMessageId: String? = null
    private var telegramAuthSession: TelegramAuthSession? = null
    private var telegramJob: Job? = null
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
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = serviceEnded()

        override fun onBindingDied(name: ComponentName) = serviceEnded()

        override fun onNullBinding(name: ComponentName) = serviceEnded()
    }

    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    init {
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
        refreshTelegram()
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
        val workingDirectory = graph.platform.activeWorkspacePath()
        if (workingDirectory == null) {
            mutableState.update { it.copy(status = "Select an accessible workspace in Settings") }
            return
        }
        val clientMessageId = UUID.randomUUID().toString()
        val request = AgentTurnRequest(
            prompt = before.draft.trim(),
            clientMessageId = clientMessageId,
            model = before.selectedModel,
            effort = before.selectedEffort,
            serviceTier = before.selectedServiceTier,
            approvalPreset = before.approvalPreset,
            capabilities = before.selectedCapabilities,
            workingDirectory = workingDirectory,
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
        mutableState.update { it.copy(draft = value) }
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

    fun showSpeedSelector() {
        mutableState.update { it.copy(popup = ChatPopup.SPEED) }
    }

    fun showApprovalSelector() {
        mutableState.update { it.copy(popup = ChatPopup.APPROVAL) }
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
            val tier = it.selectedServiceTier?.takeIf { selected ->
                model.serviceTiers.any { option -> option.id == selected }
            } ?: model.defaultServiceTier
            it.copy(
                selectedModel = model.id,
                selectedEffort = effort,
                selectedServiceTier = tier,
                popup = ChatPopup.EFFORT,
            )
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

    fun selectSpeed(tier: String?) {
        val current = mutableState.value
        val model = current.models.firstOrNull { it.id == current.selectedModel } ?: return
        if (tier != null && model.serviceTiers.none { it.id == tier }) return
        mutableState.update { it.copy(selectedServiceTier = tier, popup = ChatPopup.NONE) }
        persistSelection()
    }

    fun selectApproval(preset: AgentApprovalPreset) {
        mutableState.update { it.copy(approvalPreset = preset, popup = ChatPopup.NONE) }
        persistSelection()
    }

    fun resolveCodexApproval(requestId: String, accept: Boolean) {
        serviceController?.resolveApproval(requestId, accept)
    }

    fun connectTelegram(phoneNumber: String) {
        if (telegramJob?.isActive == true) return
        cancelTelegramAuthentication()
        mutableState.update {
            it.copy(telegramBusy = true, telegramAuthPrompt = null, telegramError = null)
        }
        telegramJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = graph.platform.startTelegramAuthentication(phoneNumber)
                if (!isActive) {
                    session.close()
                    return@launch
                }
                try {
                    telegramAuthSession = session
                    readTelegramEvent(session)
                } catch (error: Exception) {
                    session.close()
                    telegramAuthSession = null
                    throw error
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(telegramBusy = false, telegramError = error.message ?: "Telegram login failed")
                }
            }
        }
    }

    fun disconnectTelegram() {
        if (telegramJob?.isActive == true) return
        cancelTelegramAuthentication()
        mutableState.update { it.copy(telegramBusy = true, telegramError = null) }
        telegramJob = viewModelScope.launch(Dispatchers.IO) {
            val disconnected = runCatching { graph.platform.disconnectTelegram() }.getOrDefault(false)
            mutableState.update {
                if (disconnected) {
                    it.copy(
                        status = "Telegram integration disconnected",
                        telegramConnected = false,
                        telegramUsername = null,
                        telegramBusy = false,
                    )
                } else {
                    it.copy(telegramBusy = false, telegramError = "Telegram could not be disconnected")
                }
            }
        }
    }

    fun submitTelegramAuthentication(value: String) {
        val session = telegramAuthSession ?: return
        if (value.isBlank() || telegramJob?.isActive == true) return
        mutableState.update { it.copy(telegramBusy = true, telegramError = null) }
        telegramJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { session.answer(value.trim()) }
                .onSuccess { readTelegramEvent(session) }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(telegramBusy = false, telegramError = error.message ?: "Telegram login failed")
                    }
                }
        }
    }

    fun cancelTelegramAuthentication() {
        telegramJob?.cancel()
        telegramJob = null
        telegramAuthSession?.close()
        telegramAuthSession = null
        mutableState.update {
            it.copy(telegramBusy = false, telegramAuthPrompt = null, telegramError = null)
        }
    }

    fun addCapability(capability: AgentCapability) {
        mutableState.update {
            it.copy(
                selectedCapabilities = it.selectedCapabilities + capability,
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

    fun workspaceRoots(): List<String> = runCatching { graph.platform.workspaceRoots() }.getOrDefault(emptyList())

    fun workspaceDirectories(path: String?): List<String> =
        runCatching { graph.platform.workspaceDirectories(path) }.getOrDefault(emptyList())

    fun workspaceParent(path: String): String? = runCatching { graph.platform.workspaceParent(path) }.getOrNull()

    fun selectWorkspace(path: String) {
        runCatching { graph.platform.selectWorkspace(path) }
            .onSuccess { selected ->
                mutableState.update {
                    it.copy(status = "Workspace selected", workspacePath = selected, storagePermissionGranted = true)
                }
            }
            .onFailure { mutableState.update { state -> state.copy(status = "Workspace selection failed") } }
    }

    fun clearWorkspace() {
        runCatching { graph.platform.clearWorkspace() }
            .onSuccess { mutableState.update { it.copy(status = "Workspace cleared", workspacePath = null) } }
            .onFailure { mutableState.update { it.copy(status = "Workspace could not be cleared") } }
    }

    fun refreshStorage() {
        mutableState.update {
            it.copy(
                storagePermissionGranted = graph.platform.hasStoragePermission(),
                workspacePath = graph.platform.currentWorkspacePath(),
                backgroundNotificationVisible = serviceController?.let {
                    notificationsEnabled?.invoke() ?: false
                } ?: it.backgroundNotificationVisible,
                telegramAvailable = graph.platform.telegramAvailable(),
            )
        }
        refreshTelegram()
    }

    override fun onCleared() {
        serviceStateJob?.cancel()
        telegramJob?.cancel()
        telegramAuthSession?.close()
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
                    selectedServiceTier = event.serviceTier ?: it.selectedServiceTier,
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

            is AgentEvent.ApprovalRequested -> mutableState.update {
                it.copy(status = "Approval needed", codexApproval = event)
            }

            is AgentEvent.WorkActivityChanged -> Unit
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
            val tier = selected?.let { model ->
                current.selectedServiceTier?.takeIf { saved ->
                    model.serviceTiers.any { it.id == saved }
                } ?: model.defaultServiceTier
            }
            current.copy(
                models = models,
                conversations = conversations,
                selectedModel = selected?.id ?: current.selectedModel,
                selectedEffort = effort ?: current.selectedEffort,
                selectedServiceTier = tier,
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
                if (current.selectedServiceTier == null) remove(LAST_SPEED)
                else putString(LAST_SPEED, current.selectedServiceTier)
                putString(APPROVAL_POLICY, current.approvalPreset.name)
            }
            .apply()
    }

    private fun refreshTelegram() {
        if (!graph.platform.telegramAvailable() || telegramAuthSession != null || telegramJob?.isActive == true) {
            return
        }
        telegramJob = viewModelScope.launch(Dispatchers.IO) {
            val status = runCatching { graph.platform.telegramStatus() }.getOrNull()
            mutableState.update {
                it.copy(
                    telegramAvailable = status?.available ?: graph.platform.telegramAvailable(),
                    telegramConnected = status?.connected == true,
                    telegramUsername = status?.username,
                    telegramBusy = false,
                )
            }
        }
    }

    private fun readTelegramEvent(session: TelegramAuthSession) {
        when (val event = session.nextEvent()) {
            is TelegramAuthEvent.Prompt -> mutableState.update {
                it.copy(telegramBusy = false, telegramAuthPrompt = event.prompt, telegramError = null)
            }

            is TelegramAuthEvent.Connected -> {
                session.close()
                telegramAuthSession = null
                val status = runCatching { graph.platform.telegramStatus() }.getOrNull()
                mutableState.update {
                    it.copy(
                        status = "Telegram integration connected",
                        telegramConnected = true,
                        telegramUsername = status?.username ?: event.username,
                        telegramAuthPrompt = null,
                        telegramBusy = false,
                        telegramError = null,
                    )
                }
            }

            is TelegramAuthEvent.Failed -> {
                session.close()
                telegramAuthSession = null
                mutableState.update {
                    it.copy(telegramAuthPrompt = null, telegramBusy = false, telegramError = event.message)
                }
            }
        }
    }

    private fun applySessionState(
        session: ForegroundSessionState,
        notificationVisible: Boolean,
    ) {
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
                selectedServiceTier = if (restoreSelection) {
                    session.activeServiceTier ?: current.selectedServiceTier
                } else current.selectedServiceTier,
                signInUrl = session.signInUrl,
                codexApproval = session.pendingApproval,
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

    private companion object {
        const val CHAT_PREFERENCES = "chat-ui"
        const val LAST_MODEL = "last-model"
        const val LAST_EFFORT = "last-effort"
        const val LAST_SPEED = "last-speed"
        const val APPROVAL_POLICY = "approval-policy"
        const val EXISTING_SERVICE_BIND_TIMEOUT_MILLIS = 1_000L
    }
}
