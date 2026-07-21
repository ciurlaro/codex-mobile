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
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
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
    val pinnedConversationIds: Set<String> = emptySet(),
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
    val authenticationBusy: Boolean = false,
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
            pinnedConversationIds = chatPreferences
                .getStringSet(PINNED_CONVERSATIONS, emptySet())
                .orEmpty()
                .toSet(),
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
                    setAuthenticationHandoffPending(false)
                    mutableState.update {
                        it.copy(status = message, backgroundActive = false, authenticationBusy = false)
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
        if (authenticationHandoffPending()) {
            mutableState.update {
                it.copy(status = "Completing sign-in…", authenticationBusy = true)
            }
            authenticate()
        }
        refreshTelegram()
    }

    fun authenticate() {
        setAuthenticationHandoffPending(true)
        if (
            serviceController != null &&
            (!mutableState.value.backgroundActive || serviceController?.state?.value?.terminal == true)
        ) {
            releaseServiceBinding()
        }
        mutableState.update {
            it.copy(
                status = "Starting protected background work…",
                signInUrl = null,
                authenticationBusy = true,
            )
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
            setAuthenticationHandoffPending(false)
            mutableState.update {
                it.copy(
                    status = "Android could not start background work; keep Codex Mobile visible and try again",
                    backgroundActive = false,
                    authenticationBusy = false,
                )
            }
        }
    }

    fun cancelAuthentication() {
        setAuthenticationHandoffPending(false)
        mutableState.update { it.copy(status = "Cancelling sign-in…", authenticationBusy = false) }
        serviceController?.cancelAuthentication()
            ?: mutableState.update { it.copy(status = "Ready to sign in") }
    }

    fun browserUnavailable() {
        setAuthenticationHandoffPending(false)
        mutableState.update {
            it.copy(status = "No browser can open the ChatGPT sign-in page", authenticationBusy = false)
        }
    }

    fun submit(prompt: String) {
        updateDraft(prompt)
        sendMessage()
    }

    fun sendMessage() {
        val before = mutableState.value
        val shellCommand = before.draft.shellCommandOrNull()
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
        val submitted = if (shellCommand != null) {
            controller.submitShell(
                shellCommand,
                AgentRuntimeSettings(
                    approvalPreset = before.approvalPreset,
                    serviceTier = before.selectedServiceTier,
                    workingDirectory = workingDirectory,
                ),
            )
        } else {
            controller.submit(request)
        }
        if (!submitted) return

        val assistantId = "stream-$clientMessageId"
        activeAssistantMessageId = assistantId
        mutableState.update {
            it.copy(
                status = if (shellCommand == null) "Thinking" else "Running command",
                messages = it.messages + listOf(
                    ChatMessage(
                        id = "user-$clientMessageId",
                        role = AgentMessageRole.USER,
                        text = request.prompt,
                        capabilities = if (shellCommand == null) request.capabilities else emptySet(),
                        model = request.model,
                        effort = request.effort,
                    ),
                    ChatMessage(
                        id = assistantId,
                        role = AgentMessageRole.CODEX,
                        text = "",
                        streaming = true,
                        shellCommand = shellCommand,
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
        val current = mutableState.value
        if (
            !controller.openConversation(
                sessionId,
                AgentRuntimeSettings(
                    approvalPreset = current.approvalPreset,
                    serviceTier = current.selectedServiceTier,
                    workingDirectory = graph.platform.activeWorkspacePath(),
                ),
            )
        ) return
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

    fun togglePinConversation(sessionId: SessionId) {
        val current = mutableState.value
        if (current.conversations.none { it.sessionId == sessionId }) return
        val updated = current.pinnedConversationIds.toMutableSet().apply {
            if (!add(sessionId.value)) remove(sessionId.value)
        }.toSet()
        mutableState.update { it.copy(pinnedConversationIds = updated) }
        persistPinnedConversations(updated)
    }

    fun renameConversation(sessionId: SessionId, title: String) {
        val snapshot = title.trim().take(MAX_CONVERSATION_TITLE_LENGTH)
        if (snapshot.isEmpty()) {
            mutableState.update { it.copy(status = "Conversation name cannot be empty") }
            return
        }
        val controller = serviceController ?: return
        viewModelScope.launch {
            try {
                controller.renameConversation(sessionId, snapshot)
                mutableState.update { current ->
                    current.copy(
                        status = "Conversation renamed",
                        conversations = current.conversations.map { conversation ->
                            if (conversation.sessionId == sessionId) conversation.copy(title = snapshot)
                            else conversation
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(status = "Conversation could not be renamed") }
            }
        }
    }

    fun deleteConversation(sessionId: SessionId) {
        val controller = serviceController ?: return
        val current = mutableState.value
        if (current.turnActive && current.sessionId == sessionId) {
            mutableState.update { it.copy(status = "Stop the current response before deleting this chat") }
            return
        }
        viewModelScope.launch {
            try {
                controller.deleteConversation(sessionId)
                val updatedPins = mutableState.value.pinnedConversationIds - sessionId.value
                persistPinnedConversations(updatedPins)
                if (mutableState.value.sessionId == sessionId) {
                    controller.freshChat()
                    pendingConversationId = null
                    selectionRestoredSessionId = null
                    activeAssistantMessageId = null
                    mutableState.update {
                        it.copy(
                            status = "Conversation deleted",
                            streamedText = "",
                            sessionId = null,
                            conversations = it.conversations.filterNot { item -> item.sessionId == sessionId },
                            pinnedConversationIds = updatedPins,
                            messages = emptyList(),
                            draft = "",
                            selectedCapabilities = emptySet(),
                            drawerOpen = false,
                            popup = ChatPopup.NONE,
                            historySearch = "",
                            conversationLoading = false,
                        )
                    }
                } else {
                    mutableState.update {
                        it.copy(
                            status = "Conversation deleted",
                            conversations = it.conversations.filterNot { item -> item.sessionId == sessionId },
                            pinnedConversationIds = updatedPins,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(status = "Conversation could not be deleted") }
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
        setAuthenticationHandoffPending(false)
        runCatching { appContext.startService(CodexForegroundService.stopIntent(appContext)) }
            .onFailure {
                mutableState.update { state -> state.copy(status = "Background work could not be stopped") }
            }
    }

    fun signOut() {
        setAuthenticationHandoffPending(false)
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
        setAuthenticationHandoffPending(false)
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
            is AgentEvent.AuthenticationRequired -> {
                setAuthenticationHandoffPending(true)
                mutableState.update {
                    it.copy(
                        status = "Finish sign-in in your browser",
                        signInUrl = event.signInUrl,
                        authenticationBusy = false,
                    )
                }
            }

            AgentEvent.Authenticated -> {
                setAuthenticationHandoffPending(false)
                mutableState.update {
                    it.copy(
                        status = "Signed in",
                        authenticated = true,
                        signInUrl = null,
                        authenticationBusy = false,
                    )
                }
            }

            is AgentEvent.SessionOpened -> {
                setAuthenticationHandoffPending(false)
                mutableState.update {
                    it.copy(
                        status = "Ready",
                        authenticated = true,
                        authenticationBusy = false,
                        sessionId = event.sessionId,
                        selectedModel = event.model ?: it.selectedModel,
                        selectedEffort = event.effort ?: it.selectedEffort,
                        selectedServiceTier = event.serviceTier ?: it.selectedServiceTier,
                    )
                }
            }

            is AgentEvent.TextDelta -> mutableState.update {
                if (it.sessionId == event.sessionId) it.copy(streamedText = it.streamedText + event.text)
                else it
            }

            is AgentEvent.ShellOutputDelta -> mutableState.update {
                if (it.sessionId == event.sessionId) it.copy(streamedText = it.streamedText + event.text)
                else it
            }

            is AgentEvent.ShellCommandCompleted -> Unit

            is AgentEvent.TurnCompleted -> mutableState.update {
                it.copy(status = "Ready", turnActive = false)
            }

            is AgentEvent.Failure -> {
                setAuthenticationHandoffPending(false)
                mutableState.update {
                    it.copy(
                        status = event.message,
                        sessionId = if (event.sessionId == null) null else it.sessionId,
                        signInUrl = null,
                        authenticationBusy = false,
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

    private fun persistPinnedConversations(ids: Set<String>) {
        chatPreferences.edit().putStringSet(PINNED_CONVERSATIONS, ids.toSet()).apply()
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
        when {
            session.authenticated -> setAuthenticationHandoffPending(false)
            session.signInUrl != null -> setAuthenticationHandoffPending(true)
            session.terminal || session.diagnosticCode != null -> setAuthenticationHandoffPending(false)
        }
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
                        message.copy(
                            text = session.streamedText,
                            streaming = session.turnActive,
                            exitCode = session.shellExitCode,
                        )
                    } else {
                        message
                    }
                }
                if (finishedTurn) {
                    messages = messages.filterNot {
                        it.id == assistantId && it.text.isEmpty() && it.shellCommand == null
                    }
                }
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
                authenticationBusy = current.authenticationBusy &&
                    !session.authenticated &&
                    session.signInUrl == null &&
                    session.diagnosticCode == null &&
                    !session.terminal,
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
        val recoverAuthentication = authenticationHandoffPending()
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
                status = when {
                    recoverAuthentication -> "Completing sign-in…"
                    it.backgroundActive -> "Background work ended"
                    else -> it.status
                },
                sessionId = null,
                authenticated = false,
                signInUrl = null,
                authenticationBusy = recoverAuthentication,
                turnActive = false,
                backgroundActive = false,
            )
        }
        if (recoverAuthentication) {
            viewModelScope.launch {
                delay(AUTHENTICATION_RECOVERY_DELAY_MILLIS)
                if (serviceController == null && authenticationHandoffPending()) authenticate()
            }
        }
    }

    private fun authenticationHandoffPending(): Boolean =
        chatPreferences.getBoolean(AUTHENTICATION_HANDOFF_PENDING, false)

    @Suppress("ApplySharedPref")
    private fun setAuthenticationHandoffPending(pending: Boolean) {
        chatPreferences.edit().putBoolean(AUTHENTICATION_HANDOFF_PENDING, pending).commit()
    }

    private companion object {
        const val CHAT_PREFERENCES = "chat-ui"
        const val LAST_MODEL = "last-model"
        const val LAST_EFFORT = "last-effort"
        const val LAST_SPEED = "last-speed"
        const val APPROVAL_POLICY = "approval-policy"
        const val PINNED_CONVERSATIONS = "pinned-conversations"
        const val AUTHENTICATION_HANDOFF_PENDING = "authentication-handoff-pending"
        const val MAX_CONVERSATION_TITLE_LENGTH = 80
        const val EXISTING_SERVICE_BIND_TIMEOUT_MILLIS = 1_000L
        const val AUTHENTICATION_RECOVERY_DELAY_MILLIS = 150L
    }
}
