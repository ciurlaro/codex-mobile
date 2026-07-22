package io.github.ciurlaro.codexmobile.app

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.platform.android.TelegramAuthEvent
import io.github.ciurlaro.codexmobile.platform.android.TelegramAuthSession
import java.util.ArrayDeque
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as CodexMobileApplication).graph
    private val appContext = application.applicationContext
    private val chatPreferences = appContext.getSharedPreferences(CHAT_PREFERENCES, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        MainUiState(
            hasStorageAccess = graph.platform.hasStoragePermission(),
            workspacePath = graph.platform.configuredWorkspacePath(),
            selectedModel = chatPreferences.getString(LAST_MODEL, null),
            selectedEffort = chatPreferences.getString(LAST_EFFORT, null),
            selectedSpeedTier = chatPreferences.getString(LAST_SPEED, null),
            pinnedConversationIds = chatPreferences
                .getStringSet(PINNED_CONVERSATIONS, emptySet())
                .orEmpty()
                .toSet(),
            approvalPreset = chatPreferences.getString(APPROVAL_POLICY, null)
                ?.let { saved -> AgentApprovalPreset.entries.firstOrNull { it.name == saved } }
                ?: AgentApprovalPreset.NEVER,
            isTelegramAvailable = graph.platform.telegramAvailable(),
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
    private var skillsRevision = 0
    private var connectorsRevision = 0
    private val pendingConnectorAuthentications = ArrayDeque<AgentConnector>()
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
                    if (session.skillsRevision != skillsRevision) {
                        skillsRevision = session.skillsRevision
                        launch { refreshCapabilities(binder.controller, forceReload = true) }
                    }
                    if (session.connectorsRevision != connectorsRevision) {
                        connectorsRevision = session.connectorsRevision
                        launch { refreshConnectors(binder.controller, forceReload = true) }
                    }
                    if (session.isAuthenticated && !chatDataRequested) {
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
                        it.copy(statusMessage = message, isBackgroundActive = false, isAuthenticationInProgress = false)
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
                        it.copy(statusMessage = "Previous background work ended unexpectedly; recovery was checked")
                    }
                    graph.markBackgroundActive(false)
                }
            }
        }
        if (authenticationHandoffPending()) {
            mutableState.update {
                it.copy(statusMessage = "Completing sign-in…", isAuthenticationInProgress = true)
            }
            authenticate()
        }
        refreshTelegram()
    }

    fun authenticate() {
        setAuthenticationHandoffPending(true)
        if (
            serviceController != null &&
            (!mutableState.value.isBackgroundActive || serviceController?.state?.value?.terminal == true)
        ) {
            releaseServiceBinding()
        }
        mutableState.update {
            it.copy(
                statusMessage = "Starting protected background work…",
                signInUrl = null,
                isAuthenticationInProgress = true,
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
                    statusMessage = "Android could not start background work; keep Codex Mobile visible and try again",
                    isBackgroundActive = false,
                    isAuthenticationInProgress = false,
                )
            }
        }
    }

    fun cancelAuthentication() {
        setAuthenticationHandoffPending(false)
        mutableState.update { it.copy(statusMessage = "Cancelling sign-in…", isAuthenticationInProgress = false) }
        serviceController?.cancelAuthentication()
            ?: mutableState.update { it.copy(statusMessage = "Ready to sign in") }
    }

    fun browserUnavailable() {
        setAuthenticationHandoffPending(false)
        mutableState.update {
            it.copy(statusMessage = "No browser can open the ChatGPT sign-in page", isAuthenticationInProgress = false)
        }
    }

    fun sendMessage() {
        val before = mutableState.value
        val shellCommand = before.draft.shellCommandOrNull()
        if (
            before.draft.isBlank() && before.selectedCapabilities.isEmpty() &&
            before.selectedInvocations.isEmpty()
        ) {
            mutableState.update { it.copy(statusMessage = "Enter a message or add a prompt tag") }
            return
        }
        if (beginOnUseAuthentication(before)) return
        val controller = serviceController
        if (controller == null) {
            mutableState.update { it.copy(statusMessage = "Start a background session first") }
            return
        }
        val workingDirectory = graph.platform.activeWorkspacePath()
        if (workingDirectory == null) {
            mutableState.update { it.copy(statusMessage = "Select an accessible workspace in Settings") }
            return
        }
        val clientMessageId = UUID.randomUUID().toString()
        val request = AgentTurnRequest(
            prompt = before.draft.trim(),
            clientMessageId = clientMessageId,
            model = before.selectedModel,
            effort = before.selectedEffort,
            serviceTier = before.selectedSpeedTier,
            approvalPreset = before.approvalPreset,
            capabilities = before.selectedCapabilities,
            invocations = before.selectedInvocations,
            workingDirectory = workingDirectory,
        )
        val submitted = if (shellCommand != null) {
            controller.submitShell(
                shellCommand,
                AgentRuntimeSettings(
                    approvalPreset = before.approvalPreset,
                    serviceTier = before.selectedSpeedTier,
                    workingDirectory = workingDirectory,
                ),
            )
        } else {
            controller.submit(request)
        }
        if (!submitted) return

        val assistantId = "stream-$clientMessageId"
        activeAssistantMessageId = assistantId
        mutableState.update { it.withSubmittedTurn(request, assistantId, shellCommand) }
    }

    fun updateDraft(value: String) {
        mutableState.update { it.copy(draft = value) }
    }

    fun openHistory() {
        mutableState.update { it.copy(isHistoryOpen = true, activeSelector = null) }
        refreshConversations()
    }

    fun closeHistory() {
        mutableState.update { it.copy(isHistoryOpen = false, historySearch = "") }
    }

    fun updateHistorySearch(value: String) {
        mutableState.update { it.copy(historySearch = value) }
    }

    fun startNewChat() {
        serviceController?.let { if (!it.startNewChat()) return }
        pendingConversationId = null
        selectionRestoredSessionId = null
        activeAssistantMessageId = null
        mutableState.update(MainUiState::withNewChat)
    }

    fun openConversation(sessionId: SessionId) {
        val controller = serviceController ?: return
        val current = mutableState.value
        if (
            !controller.openConversation(
                sessionId,
                AgentRuntimeSettings(
                    approvalPreset = current.approvalPreset,
                    serviceTier = current.selectedSpeedTier,
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
                isHistoryOpen = false,
                activeSelector = null,
                historySearch = "",
                isConversationLoading = true,
            )
        }
        viewModelScope.launch {
            try {
                val conversation = controller.readConversation(sessionId)
                if (pendingConversationId == sessionId) {
                    mutableState.update {
                        it.copy(
                            messages = conversation.messages.map { message -> message.toChatMessage() },
                            isConversationLoading = false,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (pendingConversationId == sessionId) {
                    mutableState.update {
                        it.copy(statusMessage = "Conversation history could not be loaded", isConversationLoading = false)
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
            mutableState.update { it.copy(statusMessage = "Conversation name cannot be empty") }
            return
        }
        val controller = serviceController ?: return
        viewModelScope.launch {
            try {
                controller.renameConversation(sessionId, snapshot)
                mutableState.update { current ->
                    current.copy(
                        statusMessage = "Conversation renamed",
                        conversations = current.conversations.map { conversation ->
                            if (conversation.sessionId == sessionId) conversation.copy(title = snapshot)
                            else conversation
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(statusMessage = "Conversation could not be renamed") }
            }
        }
    }

    fun deleteConversation(sessionId: SessionId) {
        val controller = serviceController ?: return
        val current = mutableState.value
        if (current.isTurnActive && current.sessionId == sessionId) {
            mutableState.update { it.copy(statusMessage = "Stop the current response before deleting this chat") }
            return
        }
        viewModelScope.launch {
            try {
                controller.deleteConversation(sessionId)
                val updatedPins = mutableState.value.pinnedConversationIds - sessionId.value
                persistPinnedConversations(updatedPins)
                if (mutableState.value.sessionId == sessionId) {
                    controller.startNewChat()
                    pendingConversationId = null
                    selectionRestoredSessionId = null
                    activeAssistantMessageId = null
                }
                mutableState.update { it.withoutConversation(sessionId) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(statusMessage = "Conversation could not be deleted") }
            }
        }
    }

    fun openSettings() {
        mutableState.update {
            it.copy(screen = AppScreen.SETTINGS, isHistoryOpen = false, activeSelector = null)
        }
    }

    fun closeSettings() {
        mutableState.update { it.copy(screen = AppScreen.CHAT, activeSelector = null) }
    }

    fun openCapabilities() {
        mutableState.update {
            it.copy(screen = AppScreen.CAPABILITIES, isHistoryOpen = false, activeSelector = null)
        }
        serviceController?.let { controller ->
            viewModelScope.launch { refreshCapabilities(controller, forceReload = false) }
        }
    }

    fun closeCapabilities() {
        mutableState.update { it.copy(screen = AppScreen.SETTINGS, selectedPlugin = null) }
    }

    fun refreshCapabilities() {
        serviceController?.let { controller ->
            viewModelScope.launch { refreshCapabilities(controller, forceReload = true) }
        }
    }

    fun selectCapabilityTab(tab: CapabilityTab) {
        mutableState.update { it.copy(capabilityTab = tab, selectedPlugin = null) }
    }

    fun searchCapabilities(query: String) {
        mutableState.update { it.copy(capabilitySearch = query) }
    }

    fun closePluginDetails() {
        mutableState.update { it.copy(selectedPlugin = null) }
    }

    fun openPlugin(plugin: AgentPluginReference) {
        val controller = serviceController ?: return
        mutableState.update { it.copy(isCapabilitiesLoading = true, capabilityError = null) }
        viewModelScope.launch {
            runCatching { controller.readPlugin(plugin) }
                .onSuccess { detail ->
                    mutableState.update { it.copy(selectedPlugin = detail, isCapabilitiesLoading = false) }
                }
                .onFailure { error -> capabilityFailure(error) }
        }
    }

    fun toggleSkill(path: String, enabled: Boolean) = capabilityMutation("Skill could not be updated") {
        serviceController?.setSkillEnabled(path, enabled)
        serviceController?.let { refreshCapabilities(it, forceReload = true) }
    }

    fun installPlugin(plugin: AgentPluginReference) = capabilityMutation("Plugin could not be installed") {
        val result = serviceController?.installPlugin(plugin) ?: return@capabilityMutation
        mutableState.update { it.copy(pluginChangesNeedNewChat = true) }
        serviceController?.let { refreshCapabilities(it, forceReload = true) }
        if (result.authPolicy == AgentPluginAuthPolicy.ON_INSTALL) {
            enqueueConnectorAuthentication(result.connectorsNeedingAuthentication)
        }
    }

    fun uninstallPlugin(pluginId: String) = capabilityMutation("Plugin could not be removed") {
        serviceController?.uninstallPlugin(pluginId)
        mutableState.update { it.copy(pluginChangesNeedNewChat = true, selectedPlugin = null) }
        serviceController?.let { refreshCapabilities(it, forceReload = true) }
    }

    fun togglePlugin(pluginId: String, enabled: Boolean) = capabilityMutation("Plugin could not be updated") {
        serviceController?.setPluginEnabled(pluginId, enabled)
        mutableState.update { it.copy(pluginChangesNeedNewChat = true) }
        serviceController?.let { refreshCapabilities(it, forceReload = true) }
    }

    fun connectApp(connectorId: String) {
        mutableState.value.connectors.firstOrNull { it.id == connectorId }?.let(::beginAppAuthentication)
    }

    fun connectMcp(serverName: String) {
        val controller = serviceController ?: return
        viewModelScope.launch {
            runCatching { controller.startMcpOauth(serverName) }
                .onSuccess { url ->
                    mutableState.update {
                        it.copy(connectorAuthUrl = url, connectorAuthName = serverName)
                    }
                }
                .onFailure { error -> capabilityFailure(error) }
        }
    }

    fun connectorAuthenticationFinished(success: Boolean) {
        mutableState.update {
            it.copy(
                connectorAuthUrl = null,
                connectorAuthName = null,
                statusMessage = if (success) "Integration connected" else it.statusMessage,
            )
        }
        serviceController?.let { controller ->
            viewModelScope.launch { refreshConnectors(controller, forceReload = true) }
        }
        if (success) {
            beginNextConnectorAuthentication()
        } else {
            pendingConnectorAuthentications.clear()
        }
    }

    fun resolveElicitation(requestId: String, response: AgentElicitationResponse) {
        serviceController?.resolveElicitation(requestId, response)
    }

    fun openSelector(selector: ChatSelector) {
        mutableState.update { it.copy(activeSelector = selector) }
    }

    fun dismissSelector() {
        mutableState.update { it.copy(activeSelector = null) }
    }

    fun selectModel(modelId: String) {
        val model = mutableState.value.models.firstOrNull { it.id == modelId } ?: return
        mutableState.update {
            val effort = it.selectedEffort?.takeIf(model.supportedEfforts::contains)
                ?: model.defaultEffort
            val tier = it.selectedSpeedTier?.takeIf { selected ->
                model.serviceTiers.any { option -> option.id == selected }
            } ?: model.defaultServiceTier
            it.copy(
                selectedModel = model.id,
                selectedEffort = effort,
                selectedSpeedTier = tier,
                activeSelector = ChatSelector.EFFORT,
            )
        }
        persistSelection()
    }

    fun selectEffort(effort: String) {
        val current = mutableState.value
        val model = current.selectedModelOrNull() ?: return
        if (effort !in model.supportedEfforts) return
        mutableState.update { it.copy(selectedEffort = effort, activeSelector = null) }
        persistSelection()
    }

    fun selectSpeed(tier: String?) {
        val current = mutableState.value
        val model = current.selectedModelOrNull() ?: return
        if (tier != null && model.serviceTiers.none { it.id == tier }) return
        mutableState.update { it.copy(selectedSpeedTier = tier, activeSelector = null) }
        persistSelection()
    }

    fun selectApproval(preset: AgentApprovalPreset) {
        mutableState.update { it.copy(approvalPreset = preset, activeSelector = null) }
        persistSelection()
    }

    fun resolveCodexApproval(requestId: String, decision: AgentApprovalDecision) {
        serviceController?.resolveApproval(requestId, decision)
    }

    fun connectTelegram(phoneNumber: String) {
        if (telegramJob?.isActive == true) return
        cancelTelegramAuthentication()
        mutableState.update {
            it.copy(isTelegramOperationInProgress = true, telegramAuthPrompt = null, telegramError = null)
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
                    it.copy(isTelegramOperationInProgress = false, telegramError = error.message ?: "Telegram login failed")
                }
            }
        }
    }

    fun disconnectTelegram() {
        if (telegramJob?.isActive == true) return
        cancelTelegramAuthentication()
        mutableState.update { it.copy(isTelegramOperationInProgress = true, telegramError = null) }
        telegramJob = viewModelScope.launch(Dispatchers.IO) {
            val disconnected = runCatching { graph.platform.disconnectTelegram() }.getOrDefault(false)
            mutableState.update {
                if (disconnected) {
                    it.copy(
                        statusMessage = "Telegram integration disconnected",
                        isTelegramConnected = false,
                        telegramUsername = null,
                        isTelegramOperationInProgress = false,
                    )
                } else {
                    it.copy(isTelegramOperationInProgress = false, telegramError = "Telegram could not be disconnected")
                }
            }
        }
    }

    fun submitTelegramAuthentication(value: String) {
        val session = telegramAuthSession ?: return
        if (value.isBlank() || telegramJob?.isActive == true) return
        mutableState.update { it.copy(isTelegramOperationInProgress = true, telegramError = null) }
        telegramJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { session.submitAnswer(value.trim()) }
                .onSuccess { readTelegramEvent(session) }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isTelegramOperationInProgress = false, telegramError = error.message ?: "Telegram login failed")
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
            it.copy(isTelegramOperationInProgress = false, telegramAuthPrompt = null, telegramError = null)
        }
    }

    fun addCapability(capability: AgentCapability) {
        mutableState.update {
            it.copy(
                selectedCapabilities = it.selectedCapabilities + capability,
                activeSelector = null,
            )
        }
    }

    fun addInvocation(invocation: AgentInvocation) {
        mutableState.update {
            it.copy(
                selectedInvocations = (it.selectedInvocations + invocation)
                    .distinctBy(AgentInvocation::key),
                draft = it.draft.withoutActiveInvocationToken(invocation),
                activeSelector = null,
            )
        }
        beginOnUseAuthentication(mutableState.value)
    }

    fun removeInvocation(key: String) {
        mutableState.update {
            it.copy(selectedInvocations = it.selectedInvocations.filterNot { invocation -> invocation.key == key })
        }
    }

    fun removeCapability(capability: AgentCapability) {
        mutableState.update { it.copy(selectedCapabilities = it.selectedCapabilities - capability) }
    }

    fun cancelTurn() {
        serviceController?.cancelTurn()
    }

    fun stopBackgroundWork() {
        setAuthenticationHandoffPending(false)
        runCatching { appContext.startService(CodexForegroundService.stopIntent(appContext)) }
            .onFailure {
                mutableState.update { state -> state.copy(statusMessage = "Background work could not be stopped") }
            }
    }

    fun signOut() {
        setAuthenticationHandoffPending(false)
        mutableState.update {
            it.copy(statusMessage = "Signing out…", signInUrl = null)
        }
        signOutAction?.invoke() ?: run {
            signOutPending = true
            if (!bindService(Context.BIND_AUTO_CREATE)) {
                signOutPending = false
                mutableState.update { it.copy(statusMessage = "ChatGPT sign-out could not start; try again") }
            }
        }
    }

    fun eraseAppData() {
        setAuthenticationHandoffPending(false)
        mutableState.update { it.copy(statusMessage = "Erasing Codex Mobile data…") }
        val accepted = appContext.getSystemService(ActivityManager::class.java)
            .clearApplicationUserData()
        if (!accepted) {
            mutableState.update { it.copy(statusMessage = "Android could not erase app data; try again") }
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
                    it.copy(statusMessage = "Workspace selected", workspacePath = selected, hasStorageAccess = true)
                }
            }
            .onFailure { mutableState.update { state -> state.copy(statusMessage = "Workspace selection failed") } }
    }

    fun clearWorkspace() {
        runCatching { graph.platform.clearWorkspace() }
            .onSuccess { mutableState.update { it.copy(statusMessage = "Workspace cleared", workspacePath = null) } }
            .onFailure { mutableState.update { it.copy(statusMessage = "Workspace could not be cleared") } }
    }

    fun refreshStorage() {
        mutableState.update {
            it.copy(
                hasStorageAccess = graph.platform.hasStoragePermission(),
                workspacePath = graph.platform.configuredWorkspacePath(),
                isBackgroundNotificationVisible = serviceController?.let {
                    notificationsEnabled?.invoke() ?: false
                } ?: it.isBackgroundNotificationVisible,
                isTelegramAvailable = graph.platform.telegramAvailable(),
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
                current.selectedSpeedTier?.takeIf { saved ->
                    model.serviceTiers.any { it.id == saved }
                } ?: model.defaultServiceTier
            }
            current.copy(
                models = models,
                conversations = conversations,
                selectedModel = selected?.id ?: current.selectedModel,
                selectedEffort = effort ?: current.selectedEffort,
                selectedSpeedTier = tier,
            )
        }
        persistSelection()
        refreshCapabilities(controller, forceReload = false)
    }

    private suspend fun refreshCapabilities(
        controller: ForegroundSessionController,
        forceReload: Boolean,
    ) {
        val workingDirectory = graph.platform.activeWorkspacePath() ?: return
        mutableState.update { it.copy(isCapabilitiesLoading = true, capabilityError = null) }
        val skills = runCatching { controller.listSkills(workingDirectory, forceReload) }
        val plugins = runCatching { controller.listPlugins(workingDirectory) }
        refreshConnectors(controller, forceReload)
        val errors = buildList {
            skills.exceptionOrNull()?.message?.let(::add)
            plugins.exceptionOrNull()?.message?.let(::add)
            skills.getOrNull()?.errors?.let(::addAll)
            plugins.getOrNull()?.errors?.let(::addAll)
        }
        mutableState.update {
            it.copy(
                skills = skills.getOrNull()?.skills ?: it.skills,
                plugins = plugins.getOrNull()?.plugins ?: it.plugins,
                isCapabilitiesLoading = false,
                capabilityError = errors.distinct().joinToString("\n").ifBlank { null },
            )
        }
    }

    private suspend fun refreshConnectors(
        controller: ForegroundSessionController,
        forceReload: Boolean,
    ) {
        val connectors = runCatching { controller.listConnectors(forceReload) }
        val servers = runCatching { controller.listMcpServers() }
        mutableState.update {
            it.copy(
                connectors = connectors.getOrNull() ?: it.connectors,
                mcpServers = servers.getOrNull() ?: it.mcpServers,
            )
        }
    }

    private fun capabilityMutation(message: String, block: suspend () -> Unit) {
        mutableState.update { it.copy(isCapabilitiesLoading = true, capabilityError = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { mutableState.update { it.copy(isCapabilitiesLoading = false) } }
                .onFailure { error -> capabilityFailure(error, message) }
        }
    }

    private fun capabilityFailure(error: Throwable, fallback: String = "Capability request failed") {
        mutableState.update {
            it.copy(
                isCapabilitiesLoading = false,
                capabilityError = error.message?.take(300) ?: fallback,
            )
        }
    }

    private fun beginAppAuthentication(connector: AgentConnector) {
        val url = connector.installUrl ?: return
        mutableState.update {
            it.copy(connectorAuthUrl = url, connectorAuthName = connector.id)
        }
    }

    private fun enqueueConnectorAuthentication(connectors: List<AgentConnector>) {
        val known = buildSet {
            mutableState.value.connectorAuthName?.let(::add)
            pendingConnectorAuthentications.mapTo(this, AgentConnector::id)
        }
        connectors
            .filter { !it.isAccessible && it.installUrl != null && it.id !in known }
            .distinctBy(AgentConnector::id)
            .forEach(pendingConnectorAuthentications::addLast)
        if (mutableState.value.connectorAuthUrl == null) beginNextConnectorAuthentication()
    }

    private fun beginNextConnectorAuthentication() {
        pendingConnectorAuthentications.pollFirst()?.let(::beginAppAuthentication)
    }

    private fun beginOnUseAuthentication(state: MainUiState): Boolean {
        val connectors = state.connectorsNeedingOnUseAuthentication()
        if (connectors.isEmpty()) return false
        enqueueConnectorAuthentication(connectors)
        mutableState.update { it.copy(statusMessage = "Connect the selected plugin to continue") }
        return true
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
                putOrRemove(LAST_MODEL, current.selectedModel)
                putOrRemove(LAST_EFFORT, current.selectedEffort)
                putOrRemove(LAST_SPEED, current.selectedSpeedTier)
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
            val statusMessage = runCatching { graph.platform.telegramStatus() }.getOrNull()
            mutableState.update {
                it.copy(
                    isTelegramAvailable = statusMessage?.available ?: graph.platform.telegramAvailable(),
                    isTelegramConnected = statusMessage?.connected == true,
                    telegramUsername = statusMessage?.username,
                    isTelegramOperationInProgress = false,
                )
            }
        }
    }

    private fun readTelegramEvent(session: TelegramAuthSession) {
        when (val event = session.awaitEvent()) {
            is TelegramAuthEvent.Prompt -> mutableState.update {
                it.copy(isTelegramOperationInProgress = false, telegramAuthPrompt = event.prompt, telegramError = null)
            }

            is TelegramAuthEvent.Connected -> {
                session.close()
                telegramAuthSession = null
                val statusMessage = runCatching { graph.platform.telegramStatus() }.getOrNull()
                mutableState.update {
                    it.copy(
                        statusMessage = "Telegram integration connected",
                        isTelegramConnected = true,
                        telegramUsername = statusMessage?.username ?: event.username,
                        telegramAuthPrompt = null,
                        isTelegramOperationInProgress = false,
                        telegramError = null,
                    )
                }
            }

            is TelegramAuthEvent.Failed -> {
                session.close()
                telegramAuthSession = null
                mutableState.update {
                    it.copy(telegramAuthPrompt = null, isTelegramOperationInProgress = false, telegramError = event.message)
                }
            }
        }
    }

    private fun applySessionState(
        session: ForegroundSessionState,
        notificationVisible: Boolean,
    ) {
        when {
            session.isAuthenticated -> setAuthenticationHandoffPending(false)
            session.signInUrl != null -> setAuthenticationHandoffPending(true)
            session.terminal || session.diagnosticCode != null -> setAuthenticationHandoffPending(false)
        }
        val before = mutableState.value
        val finishedTurn = before.isTurnActive && !session.isTurnActive
        val assistantId = activeAssistantMessageId
        val restoreSelection = session.sessionId != null &&
            pendingConversationId == session.sessionId &&
            selectionRestoredSessionId != session.sessionId
        if (restoreSelection) selectionRestoredSessionId = session.sessionId
        mutableState.update { current ->
            val messages = assistantId?.let {
                current.messages.withStreamingAssistant(
                    assistantMessageId = it,
                    text = session.streamedText,
                    isStreaming = session.isTurnActive,
                    exitCode = session.shellExitCode,
                )
            } ?: current.messages
            current.copy(
                statusMessage = session.statusMessage,
                streamedText = session.streamedText,
                sessionId = session.sessionId,
                isAuthenticated = session.isAuthenticated,
                messages = messages,
                selectedModel = if (restoreSelection) session.activeModel ?: current.selectedModel
                else current.selectedModel,
                selectedEffort = if (restoreSelection) session.activeEffort ?: current.selectedEffort
                else current.selectedEffort,
                selectedSpeedTier = if (restoreSelection) {
                    session.activeServiceTier ?: current.selectedSpeedTier
                } else current.selectedSpeedTier,
                signInUrl = session.signInUrl,
                isAuthenticationInProgress = current.isAuthenticationInProgress &&
                    !session.isAuthenticated &&
                    session.signInUrl == null &&
                    session.diagnosticCode == null &&
                    !session.terminal,
                codexApproval = session.pendingApproval,
                pendingElicitation = session.pendingElicitation,
                isTurnActive = session.isTurnActive,
                isBackgroundActive = !session.terminal,
                isBackgroundNotificationVisible = notificationVisible,
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
                statusMessage = when {
                    recoverAuthentication -> "Completing sign-in…"
                    it.isBackgroundActive -> "Background work ended"
                    else -> it.statusMessage
                },
                sessionId = null,
                isAuthenticated = false,
                signInUrl = null,
                isAuthenticationInProgress = recoverAuthentication,
                isTurnActive = false,
                isBackgroundActive = false,
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

private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
    if (value == null) remove(key) else putString(key, value)
}
