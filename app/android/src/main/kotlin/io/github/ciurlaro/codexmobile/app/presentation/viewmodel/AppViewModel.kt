package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.app.lifecycle.CodexMobileApplication
import io.github.ciurlaro.codexmobile.app.persistence.AppPreferencesStore
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.planCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.withoutActiveInvocationToken
import io.github.ciurlaro.codexmobile.app.presentation.invocation.withRecentInvocation
import io.github.ciurlaro.codexmobile.app.presentation.mapper.toChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionActionError
import io.github.ciurlaro.codexmobile.app.presentation.model.CustomExtensionSource
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.afterExpiry
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionRemoval
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_URL
import io.github.ciurlaro.codexmobile.app.presentation.model.OPENAI_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.canonicalPluginSourceId
import io.github.ciurlaro.codexmobile.app.presentation.model.enabledMarketplaceNames
import io.github.ciurlaro.codexmobile.app.presentation.model.initialExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.reconcilePendingPluginSetups
import io.github.ciurlaro.codexmobile.app.presentation.state.withNewChat
import io.github.ciurlaro.codexmobile.app.presentation.state.withStreamingAssistant
import io.github.ciurlaro.codexmobile.app.presentation.state.withSubmittedTurn
import io.github.ciurlaro.codexmobile.app.presentation.state.withoutConversation
import io.github.ciurlaro.codexmobile.app.presentation.state.connectorsNeedingOnUseAuthentication
import io.github.ciurlaro.codexmobile.app.presentation.state.selectedModelOrNull
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionController
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionState
import io.github.ciurlaro.codexmobile.app.session.background.CodexForegroundService
import io.github.ciurlaro.codexmobile.app.session.background.CodexServiceConnection
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.core.PLAN_CLIENT_MESSAGE_PREFIX
import io.github.ciurlaro.codexmobile.core.AgentHook
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val container = (application as CodexMobileApplication).container
    private val uiPreferences = AppPreferencesStore(appContext)
    private val initialExtensionSources = initialExtensionSourceSelection(
        savedKnownIds = uiPreferences.savedKnownExtensionSourceIds,
        savedEnabledIds = uiPreferences.savedEnabledExtensionSourceIds,
        savedCustomSources = uiPreferences.savedCustomExtensionSources,
        appWasUpgraded = uiPreferences.appWasUpgraded,
    )
    private val restoredPendingPluginSetups = uiPreferences.pendingPluginSetups
    private val mutableState = MutableStateFlow(
        AppUiState(
            hasStorageAccess = container.platform.hasStoragePermission(),
            workspacePath = container.platform.configuredWorkspacePath(),
            selectedModel = uiPreferences.selectedModel,
            selectedEffort = uiPreferences.selectedEffort,
            selectedSpeedTier = uiPreferences.selectedSpeedTier,
            pinnedConversationIds = uiPreferences.pinnedConversationIds,
            recentInvocationKeys = uiPreferences.recentInvocationKeys,
            approvalPreset = uiPreferences.approvalPreset,
            providerSettings = container.platform.providerSettings(),
            knownExtensionSourceIds = initialExtensionSources.knownIds,
            enabledExtensionSourceIds = initialExtensionSources.enabledIds,
            customExtensionSources = initialExtensionSources.customSources,
            pendingPluginSetups = restoredPendingPluginSetups,
        ),
    )
    private var serviceController: CodexSessionController? = null
    private var serviceStartPending = false
    private var serviceStateJob: Job? = null
    private var notificationsEnabled: (() -> Boolean)? = null
    private var signOutAction: (() -> Unit)? = null
    private var signOutPending = false
    private var chatDataRequested = false
    private var activeAssistantMessageId: String? = null
    private var pendingConversationId: SessionId? = null
    private var selectionRestoredSessionId: SessionId? = null
    private var skillsRevision = 0
    private var pluginsRevision = 0
    private var connectorsRevision = 0
    private var skillsJob: Job? = null
    private var availableSkillsJob: Job? = null
    private var pluginsJob: Job? = null
    private var pluginRefreshPending = false
    private var reconciledPluginSourceIds = emptySet<String>()
    private var extensionSourceJob: Job? = null
    private var extensionNoticeJob: Job? = null
    private var integrationsLoaded = restoredPendingPluginSetups.isNotEmpty()
    private val pendingConnectorAuthentications = ArrayDeque<AgentConnector>()
    private var connectorAuthenticationJob: Job? = null
    private var connectorRefreshJob: Job? = null
    private val connectorRefreshMutex = Mutex()
    internal var serviceInstanceId: String? = null
        private set

    private val serviceConnection = CodexServiceConnection(
        context = appContext,
        onConnected = ::serviceConnected,
        onEnded = ::serviceEnded,
    )

    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    init {
        persistExtensionSourceSelection()
        viewModelScope.launch {
            container.backgroundSessions.failure.collect { failure ->
                failure?.let { message ->
                    serviceStartPending = false
                    setAuthenticationHandoffPending(false)
                    mutableState.update {
                        it.copy(statusMessage = message, isBackgroundActive = false, isAuthenticationInProgress = false)
                    }
                }
            }
        }
        val backgroundWasActive = container.backgroundSessions.wasActive()
        serviceConnection.bind(flags = 0)
        if (backgroundWasActive) {
            viewModelScope.launch {
                delay(EXISTING_SERVICE_BIND_TIMEOUT_MILLIS)
                if (serviceController == null && container.backgroundSessions.wasActive()) {
                    serviceConnection.unbind()
                    mutableState.update {
                        it.copy(statusMessage = "Previous background work ended unexpectedly; recovery was checked")
                    }
                    container.backgroundSessions.markActive(false)
                }
            }
        }
        if (authenticationHandoffPending()) {
            mutableState.update {
                it.copy(statusMessage = "Completing sign-in…", isAuthenticationInProgress = true)
            }
            authenticate()
        }
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
        if (serviceStartPending) return
        serviceStartPending = true
        val authorization = container.backgroundSessions.authorizeStart()
        try {
            appContext.startForegroundService(
                CodexForegroundService.startIntent(appContext, authorization, authenticate = true),
            )
            check(serviceConnection.bind(Context.BIND_AUTO_CREATE)) { "Codex service binding failed" }
        } catch (_: Exception) {
            serviceStartPending = false
            container.backgroundSessions.revokeStart(authorization)
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
        serviceStartPending = false
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

    internal fun sendMessage(): SendMessageOutcome {
        val before = mutableState.value
        val shellCommand = before.draft.shellCommandOrNull()
        val planCommand = if (shellCommand == null) before.draft.planCommandOrNull() else null
        if (
            planCommand != null && planCommand.prompt.isBlank() &&
            before.selectedCapabilities.isEmpty() && before.selectedInvocations.isEmpty()
        ) {
            mutableState.update {
                it.copy(
                    draft = "",
                    collaborationMode = AgentCollaborationMode.PLAN,
                    statusMessage = "Plan mode enabled",
                )
            }
            return SendMessageOutcome.HANDLED
        }
        if (
            before.draft.isBlank() && before.selectedCapabilities.isEmpty() &&
            before.selectedInvocations.isEmpty()
        ) {
            mutableState.update { it.copy(statusMessage = "Enter a message or add a prompt tag") }
            return SendMessageOutcome.HANDLED
        }
        val workingDirectory = container.platform.activeWorkspacePath()
        if (workingDirectory == null) {
            mutableState.update { it.copy(statusMessage = "Select an accessible workspace in Settings") }
            return SendMessageOutcome.WORKSPACE_REQUIRED
        }
        if (beginOnUseAuthentication(before)) return SendMessageOutcome.HANDLED
        val controller = serviceController
        if (controller == null) {
            mutableState.update { it.copy(statusMessage = "Start a background session first") }
            return SendMessageOutcome.HANDLED
        }
        val collaborationMode = if (planCommand != null) {
            AgentCollaborationMode.PLAN
        } else {
            before.collaborationMode
        }
        val clientMessageId = UUID.randomUUID().toString().let { id ->
            if (collaborationMode == AgentCollaborationMode.PLAN) "$PLAN_CLIENT_MESSAGE_PREFIX$id" else id
        }
        val request = AgentTurnRequest(
            prompt = planCommand?.prompt ?: before.draft.trim(),
            clientMessageId = clientMessageId,
            model = before.selectedModel,
            effort = before.selectedEffort,
            serviceTier = before.selectedSpeedTier,
            approvalPreset = before.approvalPreset,
            capabilities = before.selectedCapabilities,
            invocations = before.selectedInvocations,
            workingDirectory = workingDirectory,
            collaborationMode = collaborationMode,
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
        if (!submitted) return SendMessageOutcome.HANDLED

        val assistantId = "stream-$clientMessageId"
        activeAssistantMessageId = assistantId
        mutableState.update {
            it.copy(collaborationMode = collaborationMode)
                .withSubmittedTurn(request, assistantId, shellCommand)
        }
        return SendMessageOutcome.HANDLED
    }

    fun togglePlanMode() {
        mutableState.update {
            val next = if (it.collaborationMode == AgentCollaborationMode.PLAN) {
                AgentCollaborationMode.DEFAULT
            } else {
                AgentCollaborationMode.PLAN
            }
            it.copy(
                collaborationMode = next,
                statusMessage = if (next == AgentCollaborationMode.PLAN) {
                    "Plan mode enabled"
                } else {
                    "Default mode enabled"
                },
            )
        }
    }

    internal fun proceedWithPlan(): SendMessageOutcome {
        mutableState.update {
            it.copy(
                collaborationMode = AgentCollaborationMode.DEFAULT,
                draft = "Implement the proposed plan.",
            )
        }
        return sendMessage()
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
        resetChat(openChat = true)
    }

    private fun resetChat(openChat: Boolean = false) {
        serviceController?.let { if (!it.startNewChat()) return }
        pendingConversationId = null
        selectionRestoredSessionId = null
        activeAssistantMessageId = null
        mutableState.update { current ->
            current.withNewChat().let { reset ->
                if (openChat) reset else reset.copy(screen = current.screen)
            }
        }
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
                    workingDirectory = container.platform.activeWorkspacePath(),
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
                    val restoredMessages = conversation.messages.map { message -> message.toChatMessage() }
                    val collaborationMode = restoredMessages.lastOrNull { message ->
                        message.role == AgentMessageRole.USER && message.shellCommand == null
                    }?.collaborationMode ?: AgentCollaborationMode.DEFAULT
                    mutableState.update {
                        it.copy(
                            messages = restoredMessages,
                            collaborationMode = collaborationMode,
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
            it.copy(
                screen = AppScreen.SETTINGS,
                isHistoryOpen = false,
                activeSelector = null,
                providerSettings = container.platform.providerSettings(),
            )
        }
    }

    fun closeSettings() {
        mutableState.update { it.copy(screen = AppScreen.CHAT, activeSelector = null) }
    }

    fun openHooks() {
        mutableState.update { it.copy(screen = AppScreen.HOOKS, activeSelector = null) }
        loadHooks()
    }

    fun closeHooks() {
        mutableState.update { it.copy(screen = AppScreen.SETTINGS) }
    }

    fun refreshHooks() = loadHooks()

    fun setHookEnabled(hook: AgentHook, enabled: Boolean) {
        if (hook.isManaged) return
        val controller = serviceController ?: return
        mutableState.update { it.copy(isHooksLoading = true, hooksError = null) }
        viewModelScope.launch {
            runCatching { controller.setHookEnabled(hook.key, enabled) }
                .onSuccess { loadHooks() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(
                            isHooksLoading = false,
                            hooksError = error.message?.take(300) ?: "Hook could not be updated",
                        )
                    }
                }
        }
    }

    fun trustHook(hook: AgentHook) {
        if (hook.isManaged) return
        val controller = serviceController ?: return
        mutableState.update { it.copy(isHooksLoading = true, hooksError = null) }
        viewModelScope.launch {
            runCatching { controller.trustHook(hook.key, hook.currentHash) }
                .onSuccess { loadHooks() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(
                            isHooksLoading = false,
                            hooksError = error.message?.take(300) ?: "Hook could not be trusted",
                        )
                    }
                }
        }
    }

    private fun loadHooks() {
        val controller = serviceController
        val workingDirectory = container.platform.activeWorkspacePath()
        if (controller == null || workingDirectory == null) {
            mutableState.update {
                it.copy(isHooksLoading = false, hooksError = "Select a workspace and sign in to load hooks")
            }
            return
        }
        mutableState.update { it.copy(isHooksLoading = true, hooksError = null) }
        viewModelScope.launch {
            runCatching { controller.listHooks(workingDirectory) }
                .onSuccess { catalog ->
                    mutableState.update {
                        it.copy(
                            hooks = catalog.hooks,
                            hooksWarnings = catalog.warnings,
                            hooksError = catalog.errors.joinToString("\n").ifBlank { null },
                            isHooksLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(
                            hooksError = error.message?.take(300) ?: "Hooks could not be loaded",
                            isHooksLoading = false,
                        )
                    }
                }
        }
    }

    fun openExtensions(type: ExtensionType, returnScreen: AppScreen) {
        cancelExtensionNotice()
        mutableState.update {
            it.copy(
                screen = AppScreen.EXTENSIONS,
                extensionsReturnScreen = returnScreen,
                extensionType = type,
                extensionStatus = ExtensionStatus.INSTALLED,
                extensionSearch = "",
                extensionSourcesOpen = false,
                extensionNotice = null,
                isHistoryOpen = false,
                activeSelector = null,
            )
        }
        if (
            serviceController == null &&
            uiPreferences.hadAuthenticatedSession &&
            !mutableState.value.isAuthenticationInProgress
        ) authenticate()
        loadCurrentExtensions(forceReload = false)
    }

    fun closeExtensions() {
        cancelExtensionNotice()
        mutableState.update {
            it.copy(
                screen = it.extensionsReturnScreen,
                pendingExtensionRemoval = null,
                extensionActionError = null,
                extensionNotice = null,
                extensionSourcesOpen = false,
            )
        }
    }

    fun refreshExtensions() {
        cancelExtensionNotice()
        mutableState.update {
            it.copy(
                extensionActionError = null,
                unavailablePluginIds = emptySet(),
                extensionNotice = null,
            )
        }
        if (mutableState.value.extensionType == ExtensionType.PLUGINS) reconciledPluginSourceIds = emptySet()
        loadCurrentExtensions(forceReload = true)
        if (mutableState.value.pendingPluginSetups.isNotEmpty()) {
            serviceController?.let { controller ->
                viewModelScope.launch { refreshConnectors(controller, forceReload = true) }
            }
        }
    }

    fun selectExtensionType(type: ExtensionType) {
        mutableState.update {
            it.copy(
                extensionType = type,
                extensionStatus = if (type == ExtensionType.SKILLS && it.extensionStatus == ExtensionStatus.SETUP_PENDING) {
                    ExtensionStatus.INSTALLED
                } else {
                    it.extensionStatus
                },
                extensionSearch = "",
                extensionActionError = null,
            )
        }
        loadCurrentExtensions(forceReload = false)
    }

    fun selectExtensionStatus(status: ExtensionStatus) {
        mutableState.update {
            it.copy(
                extensionStatus = status,
                extensionSearch = "",
                extensionActionError = null,
            )
        }
        loadCurrentExtensions(forceReload = false)
    }

    fun searchExtensions(query: String) {
        mutableState.update { it.copy(extensionSearch = query) }
    }

    fun openExtensionSources() {
        cancelExtensionNotice()
        mutableState.update { it.copy(extensionSourcesOpen = true, extensionNotice = null) }
    }

    fun closeExtensionSources() {
        mutableState.update { it.copy(extensionSourcesOpen = false) }
        if (mutableState.value.extensionStatus != ExtensionStatus.INSTALLED) {
            loadCurrentExtensions(forceReload = false)
        }
    }

    fun toggleExtensionSource(sourceId: String, enabled: Boolean) {
        val normalized = canonicalPluginSourceId(sourceId)
        val current = mutableState.value
        if (normalized !in current.knownExtensionSourceIds) return
        pluginsJob?.cancel()
        pluginsJob = null
        pluginRefreshPending = false
        reconciledPluginSourceIds -= normalized
        availableSkillsJob?.cancel()
        mutableState.update {
            val enabledIds = if (enabled) it.enabledExtensionSourceIds + normalized
            else it.enabledExtensionSourceIds - normalized
            it.copy(
                enabledExtensionSourceIds = enabledIds,
                availablePlugins = it.availablePlugins.filter { plugin ->
                    it.copy(enabledExtensionSourceIds = enabledIds).isPluginMarketplaceEnabled(
                        plugin.reference.marketplaceName,
                    )
                },
                availableSkills = emptyList(),
                pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
                availableSkillsLoaded = false,
                pluginCatalogError = null,
                availableSkillsError = null,
            )
        }
        persistExtensionSourceSelection()
        if (current.extensionType == ExtensionType.PLUGINS) loadPluginCatalog(forceReload = true)
    }

    fun addExtensionSource(url: String) {
        val controller = serviceController ?: run {
            mutableState.update { it.copy(extensionSourceError = "Codex is not ready") }
            return
        }
        if (url.isBlank() || extensionSourceJob?.isActive == true) return
        val normalizedUrl = url.trim().trimEnd('/')
        mutableState.update { it.copy(extensionSourceError = null, isExtensionSourceLoading = true) }
        extensionSourceJob = viewModelScope.launch {
            val (skillResult, pluginResult) = coroutineScope {
                val skills = async { runCatching { controller.discoverGitHubSkills(normalizedUrl) } }
                val plugins = async { runCatching { controller.addPluginMarketplace(normalizedUrl) } }
                skills.await() to plugins.await()
            }
            ensureActive()
            (skillResult.exceptionOrNull() as? CancellationException)?.let { throw it }
            (pluginResult.exceptionOrNull() as? CancellationException)?.let { throw it }
            val skills = skillResult.getOrDefault(emptyList())
            val marketplaceName = pluginResult.getOrNull()
            if (skills.isEmpty() && marketplaceName == null) {
                extensionSourceJob = null
                val skillError = skillResult.exceptionOrNull()?.message ?: "no SKILL.md folders found"
                val pluginError = pluginResult.exceptionOrNull()?.message ?: "no plugin marketplace found"
                mutableState.update {
                    it.copy(
                        isExtensionSourceLoading = false,
                        extensionSourceError = "No extensions found. Skills: ${skillError.take(120)}. " +
                            "Plugins: ${pluginError.take(120)}.",
                    )
                }
                return@launch
            }
            val existing = mutableState.value.customExtensionSources.firstOrNull {
                it.url.equals(normalizedUrl, ignoreCase = true)
            }
            val source = CustomExtensionSource(
                id = existing?.id ?: "github:${UUID.nameUUIDFromBytes(normalizedUrl.lowercase().toByteArray())}",
                url = normalizedUrl,
                marketplaceName = marketplaceName ?: existing?.marketplaceName,
                supportsSkills = skills.isNotEmpty() || existing?.supportsSkills == true && skillResult.isFailure,
                supportsPlugins = marketplaceName != null || existing?.supportsPlugins == true && pluginResult.isFailure,
            )
            val notice = when {
                skills.isNotEmpty() && marketplaceName != null -> "Source added for skills and plugins"
                skills.isNotEmpty() -> "Source added for skills; plugin check failed: " +
                    (pluginResult.exceptionOrNull()?.message ?: "no marketplace found").take(120)
                marketplaceName != null -> "Source added for plugins; skill check failed: " +
                    (skillResult.exceptionOrNull()?.message ?: "no skills found").take(120)
                else -> "Source settings were preserved"
            }
            extensionSourceJob = null
            mutableState.update {
                it.copy(
                    knownExtensionSourceIds = it.knownExtensionSourceIds + source.id,
                    enabledExtensionSourceIds = it.enabledExtensionSourceIds + source.id,
                    customExtensionSources = it.customExtensionSources.filterNot { item -> item.id == source.id } + source,
                    availableSkills = (it.availableSkills + skills).distinctBy(AgentSkillPackage::id),
                    availableSkillsLoaded = false,
                    pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
                    isExtensionSourceLoading = false,
                    extensionSourceError = null,
                )
            }
            showExtensionNotice(notice)
            if (marketplaceName != null) reconciledPluginSourceIds += source.id
            persistExtensionSourceSelection()
        }
    }

    fun dismissExtensionSource() {
        extensionSourceJob?.cancel()
        extensionSourceJob = null
        mutableState.update { it.copy(extensionSourceError = null, isExtensionSourceLoading = false) }
    }

    fun installSkill(packageInfo: AgentSkillPackage) = extensionMutation(
        "skill:${packageInfo.id}",
        "Skill could not be installed",
    ) {
        serviceController?.installSkill(packageInfo)
        mutableState.update {
            it.copy(
                availableSkills = it.availableSkills.filterNot { candidate -> candidate.id == packageInfo.id },
            )
        }
        loadSkills(forceReload = true)
    }

    fun requestUninstallSkill(skill: AgentSkill) {
        if (skill.canUninstall) mutableState.update {
            it.copy(pendingExtensionRemoval = ExtensionRemoval.Skill(skill))
        }
    }

    fun installPlugin(plugin: AgentPluginReference) = extensionMutation(
        "plugin:${plugin.id}",
        "Plugin could not be installed",
    ) {
        val controller = serviceController ?: return@extensionMutation
        val installed = mutableState.value.availablePlugins
            .firstOrNull { it.reference.id == plugin.id }
            ?.copy(installed = true, enabled = true)
        val result = controller.installPlugin(plugin)
        val requiredConnectors = if (result.authPolicy == AgentPluginAuthPolicy.ON_INSTALL) {
            val detailConnectors = runCatching { controller.readPlugin(plugin).connectors }.getOrDefault(emptyList())
            (detailConnectors + result.connectorsNeedingAuthentication)
                .associateBy(AgentConnector::id)
                .values
                .toList()
        } else {
            emptyList()
        }
        val displayName = installed?.displayName ?: plugin.name.replaceFirstChar(Char::uppercase)
        mutableState.update {
            it.copy(
                installedPlugins = installed?.let { summary ->
                    (it.installedPlugins.filterNot { candidate -> candidate.reference.id == plugin.id } + summary)
                } ?: it.installedPlugins,
                availablePlugins = it.availablePlugins.filterNot { candidate -> candidate.reference.id == plugin.id },
                unavailablePluginIds = it.unavailablePluginIds - plugin.id,
                extensionActionError = null,
            )
        }
        if (requiredConnectors.isNotEmpty()) {
            integrationsLoaded = true
            setPendingPluginSetup(plugin.id, requiredConnectors.mapTo(mutableSetOf(), AgentConnector::id))
            refreshConnectors(controller, forceReload = true)
        }
        val pendingConnectorIds = mutableState.value.pendingPluginSetups[plugin.id].orEmpty()
        val setupPending = pendingConnectorIds.isNotEmpty()
        val notice = result.message ?: if (setupPending) {
            "$displayName installed · setup required"
        } else {
            "$displayName installed"
        }
        mutableState.update {
            it.copy(
                statusMessage = notice,
                extensionStatus = if (setupPending) ExtensionStatus.SETUP_PENDING else it.extensionStatus,
            )
        }
        showExtensionNotice(notice)
        loadPluginCatalog(forceReload = true)
        if (setupPending) {
            val latest = mutableState.value.connectors.associateBy(AgentConnector::id)
            enqueueConnectorAuthentication(
                pendingConnectorIds.mapNotNull { id -> latest[id] ?: requiredConnectors.firstOrNull { it.id == id } },
            )
        }
    }

    fun connectPlugin(plugin: AgentPluginReference) {
        val operationId = "connect:${plugin.id}"
        val current = mutableState.value
        if (current.extensionOperationId == operationId || current.connectorAuthName in current.pendingPluginSetups[plugin.id].orEmpty()) {
            return
        }
        extensionMutation(operationId, "Plugin setup could not be opened") {
            val controller = serviceController ?: error("Codex is not ready")
            integrationsLoaded = true
            val details = runCatching { controller.readPlugin(plugin).connectors }.getOrDefault(emptyList())
            val refreshed = refreshConnectors(controller, forceReload = true).orEmpty()
            val pendingIds = mutableState.value.pendingPluginSetups[plugin.id].orEmpty()
            if (pendingIds.isEmpty()) {
                mutableState.update {
                    it.copy(
                        statusMessage = "Plugin setup complete",
                        extensionStatus = ExtensionStatus.INSTALLED,
                    )
                }
                showExtensionNotice("Plugin setup complete")
                return@extensionMutation
            }
            val connectors = (details + refreshed)
                .associateBy(AgentConnector::id)
                .filterKeys { it in pendingIds }
                .values
                .filter { !it.isAccessible && it.installUrl != null }
            check(connectors.isNotEmpty()) { "A connection link is not available yet; refresh and try again" }
            enqueueConnectorAuthentication(connectors)
            mutableState.update { it.copy(statusMessage = "Complete plugin setup in the secure window") }
        }
    }

    private fun uninstallPlugin(plugin: AgentPluginReference, displayName: String) = extensionMutation(
        "plugin:${plugin.id}",
        "Plugin could not be removed",
    ) {
        val removed = mutableState.value.installedPlugins.firstOrNull { it.reference.id == plugin.id }
        val result = serviceController?.uninstallPlugin(plugin) ?: return@extensionMutation
        val notice = result.message ?: if (result.completed) {
            "$displayName uninstalled"
        } else {
            "$displayName could not be uninstalled"
        }
        if (result.completed) setPendingPluginSetup(plugin.id, emptySet())
        mutableState.update {
            it.copy(
                statusMessage = notice,
                installedPlugins = if (result.completed) {
                    it.installedPlugins.filterNot { candidate -> candidate.reference.id == plugin.id }
                } else {
                    it.installedPlugins
                },
                availablePlugins = if (result.completed && removed != null && it.isPluginMarketplaceEnabled(
                        removed.reference.marketplaceName,
                    )
                ) {
                    (it.availablePlugins.filterNot { candidate -> candidate.reference.id == plugin.id } +
                        removed.copy(installed = false, enabled = false))
                } else {
                    it.availablePlugins
                },
                pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
                providerSettings = container.platform.providerSettings(),
            )
        }
        showExtensionNotice(notice, isError = !result.completed)
        loadPluginCatalog(forceReload = true)
    }

    fun requestUninstallPlugin(plugin: AgentPluginReference, displayName: String) {
        mutableState.update {
            it.copy(pendingExtensionRemoval = ExtensionRemoval.Plugin(plugin, displayName))
        }
    }

    fun dismissExtensionRemoval() {
        mutableState.update { it.copy(pendingExtensionRemoval = null) }
    }

    fun confirmExtensionRemoval() {
        when (val removal = mutableState.value.pendingExtensionRemoval) {
            is ExtensionRemoval.Skill -> {
                mutableState.update { it.copy(pendingExtensionRemoval = null) }
                extensionMutation(
                    "skill:${removal.skill.path}",
                    "Skill could not be removed",
                ) {
                    serviceController?.uninstallSkill(removal.skill)
                    mutableState.update {
                        it.copy(
                            skills = it.skills.filterNot { candidate -> candidate.path == removal.skill.path },
                        )
                    }
                    mutableState.update { it.copy(availableSkillsLoaded = false) }
                    loadAvailableSkills(forceReload = false)
                }
            }
            is ExtensionRemoval.Plugin -> {
                mutableState.update { it.copy(pendingExtensionRemoval = null) }
                uninstallPlugin(removal.plugin, removal.displayName)
            }
            null -> Unit
        }
    }

    fun connectorAuthenticationReturned() {
        val connectorId = mutableState.value.connectorAuthName ?: return
        mutableState.update {
            it.copy(
                connectorAuthUrl = null,
                connectorAuthName = null,
            )
        }
        val controller = serviceController ?: run {
            pendingConnectorAuthentications.clear()
            return
        }
        connectorAuthenticationJob?.cancel()
        connectorAuthenticationJob = viewModelScope.launch {
            var connected = withTimeoutOrNull(CONNECTOR_UPDATE_WAIT_MILLIS) {
                mutableState.first { state ->
                    state.connectors.any { it.id == connectorId && it.isAccessible }
                }
            } != null
            if (!connected) {
                connected = refreshConnectors(controller, forceReload = true)
                    ?.any { it.id == connectorId && it.isAccessible } == true
            }
            if (connected) {
                mutableState.update {
                    it.copy(
                        statusMessage = "Integration connected",
                        extensionStatus = if (it.pendingPluginSetups.isEmpty()) {
                            ExtensionStatus.INSTALLED
                        } else {
                            it.extensionStatus
                        },
                    )
                }
                showExtensionNotice("Integration connected")
                beginNextConnectorAuthentication()
            } else {
                pendingConnectorAuthentications.clear()
                mutableState.update {
                    it.copy(
                        statusMessage = "Plugin setup still required",
                    )
                }
                showExtensionNotice("Plugin setup still required", isError = true)
            }
        }
    }

    fun openProviderSettings(pluginId: String) {
        val entry = mutableState.value.providerSettings.singleOrNull { it.pluginId == pluginId }
        if (entry?.activityClassName == null && entry?.removalNeedsRetry == true) {
            viewModelScope.launch {
                runCatching { container.platform.finishProviderRemoval(pluginId) }
                    .onSuccess {
                        mutableState.update { state ->
                            state.copy(
                                providerSettings = container.platform.providerSettings(),
                                statusMessage = "Provider removed",
                            )
                        }
                    }
                    .onFailure { error ->
                        mutableState.update { state ->
                            state.copy(
                                providerSettings = container.platform.providerSettings(),
                                statusMessage = error.message ?: "Provider code removal still needs retry",
                            )
                        }
                    }
            }
            return
        }
        runCatching { container.platform.openProviderSettings(pluginId) }
            .onFailure { error ->
                mutableState.update {
                    it.copy(statusMessage = error.message ?: "Provider settings are unavailable")
                }
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
                recentInvocationKeys = it.recentInvocationKeys.withRecentInvocation(invocation.key),
                draft = it.draft.withoutActiveInvocationToken(invocation),
                activeSelector = null,
            )
        }
        uiPreferences.saveRecentInvocationKeys(mutableState.value.recentInvocationKeys)
        if (invocation is AgentInvocation.Plugin && !integrationsLoaded) {
            integrationsLoaded = true
            serviceController?.let { controller ->
                viewModelScope.launch {
                    refreshConnectors(controller, forceReload = false)
                    beginOnUseAuthentication(mutableState.value)
                }
            }
        } else {
            beginOnUseAuthentication(mutableState.value)
        }
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

    fun signOut() {
        setAuthenticationHandoffPending(false)
        uiPreferences.setHadAuthenticatedSession(false)
        mutableState.update {
            it.copy(
                statusMessage = "Signing out…",
                signInUrl = null,
                installedPlugins = emptyList(),
                availablePlugins = emptyList(),
                pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
                pluginCatalogError = null,
            )
        }
        signOutAction?.invoke() ?: run {
            signOutPending = true
            if (!serviceConnection.bind(Context.BIND_AUTO_CREATE)) {
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

    fun workspaceRoots(): List<String> = runCatching { container.platform.workspaceRoots() }.getOrDefault(emptyList())

    fun workspaceDirectories(path: String?): List<String> =
        runCatching { container.platform.workspaceDirectories(path) }.getOrDefault(emptyList())

    fun workspaceParent(path: String): String? = runCatching { container.platform.workspaceParent(path) }.getOrNull()

    fun selectWorkspace(path: String) {
        runCatching { container.platform.selectWorkspace(path) }
            .onSuccess { selected ->
                mutableState.update {
                    it.copy(
                        statusMessage = "Workspace selected",
                        workspacePath = selected,
                        hasStorageAccess = true,
                        skills = emptyList(),
                        availableSkills = emptyList(),
                        installedPlugins = emptyList(),
                        availablePlugins = emptyList(),
                        unavailablePluginIds = emptySet(),
                        extensionActionError = null,
                        skillsLoaded = false,
                        availableSkillsLoaded = false,
                        pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
                        pluginCatalogError = null,
                    )
                }
                loadPluginCatalog(forceReload = true)
            }
            .onFailure { mutableState.update { state -> state.copy(statusMessage = "Workspace selection failed") } }
    }

    fun refreshStorage() {
        mutableState.update {
            it.copy(
                hasStorageAccess = container.platform.hasStoragePermission(),
                workspacePath = container.platform.configuredWorkspacePath(),
                isBackgroundNotificationVisible = serviceController?.let {
                    notificationsEnabled?.invoke() ?: false
                } ?: it.isBackgroundNotificationVisible,
                providerSettings = container.platform.providerSettings(),
            )
        }
    }

    override fun onCleared() {
        serviceStateJob?.cancel()
        skillsJob?.cancel()
        availableSkillsJob?.cancel()
        pluginsJob?.cancel()
        connectorAuthenticationJob?.cancel()
        connectorRefreshJob?.cancel()
        extensionNoticeJob?.cancel()
        serviceConnection.unbind()
        serviceController = null
        notificationsEnabled = null
        signOutAction = null
        signOutPending = false
        serviceInstanceId = null
    }

    private fun serviceConnected(binder: CodexForegroundService.LocalBinder) {
        serviceStartPending = false
        serviceController = binder.controller
        notificationsEnabled = binder::notificationsEnabled
        signOutAction = binder::signOut
        serviceInstanceId = binder.serviceInstanceId
        reconciledPluginSourceIds = emptySet()
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
                    if (mutableState.value.skillsLoaded) loadSkills(forceReload = true)
                }
                if (session.pluginsRevision != pluginsRevision) {
                    pluginsRevision = session.pluginsRevision
                    if (pluginsJob?.isActive == true) {
                        pluginRefreshPending = true
                    } else if (mutableState.value.pluginCatalogStatus != PluginCatalogStatus.NOT_LOADED) {
                        loadPluginCatalog(forceReload = true)
                    }
                    mutableState.update { it.copy(providerSettings = container.platform.providerSettings()) }
                }
                if (session.connectorsRevision != connectorsRevision) {
                    connectorsRevision = session.connectorsRevision
                    if (integrationsLoaded && connectorRefreshJob?.isActive != true) {
                        connectorRefreshJob = launch {
                            refreshConnectors(binder.controller, forceReload = false)
                        }
                    }
                }
                if (session.isAuthenticated && !chatDataRequested) {
                    chatDataRequested = true
                    launch { refreshChatData(binder.controller) }
                }
                if (session.terminal) releaseServiceBinding()
            }
        }
    }

    private suspend fun refreshChatData(controller: CodexSessionController) {
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
        loadSkills(forceReload = false)
        loadPluginCatalog(forceReload = false)
        if (mutableState.value.pendingPluginSetups.isNotEmpty()) {
            integrationsLoaded = true
            refreshConnectors(controller, forceReload = true)
        }
        if (mutableState.value.screen == AppScreen.EXTENSIONS) loadCurrentExtensions(forceReload = false)
    }

    private fun loadCurrentExtensions(forceReload: Boolean) {
        val current = mutableState.value
        when (current.extensionType) {
            ExtensionType.SKILLS -> when (current.extensionStatus) {
                ExtensionStatus.INSTALLED -> loadSkills(forceReload)
                ExtensionStatus.UNINSTALLED -> loadAvailableSkills(forceReload)
                ExtensionStatus.SETUP_PENDING, ExtensionStatus.UNAVAILABLE -> Unit
            }
            ExtensionType.PLUGINS -> loadPluginCatalog(forceReload)
        }
    }

    private fun loadSkills(forceReload: Boolean) {
        val controller = serviceController ?: return
        val current = mutableState.value
        if (!forceReload && (current.skillsLoaded || skillsJob?.isActive == true)) return
        if (forceReload) skillsJob?.cancel()
        val workingDirectory = container.platform.activeWorkspacePath() ?: return
        mutableState.update { it.copy(isSkillsLoading = true, skillsError = null) }
        skillsJob = viewModelScope.launch {
            runCatching { controller.listSkills(workingDirectory, forceReload) }
                .onSuccess { catalog ->
                    if (serviceController !== controller) return@onSuccess
                    mutableState.update {
                        it.copy(
                            skills = catalog.skills,
                            skillsLoaded = true,
                            isSkillsLoading = false,
                            skillsError = catalog.errors.distinct().joinToString("\n").ifBlank { null },
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (serviceController !== controller) return@onFailure
                    mutableState.update {
                        it.copy(
                            skillsLoaded = true,
                            isSkillsLoading = false,
                            skillsError = error.message?.take(300) ?: "Skills could not be loaded",
                        )
                    }
                }
        }
    }

    private fun loadAvailableSkills(forceReload: Boolean) {
        val selectedSources = mutableState.value
        val openAiEnabled = OPENAI_PLUGIN_SOURCE_ID in selectedSources.enabledExtensionSourceIds
        val customSources = selectedSources.customExtensionSources.filter {
            it.supportsSkills && it.id in selectedSources.enabledExtensionSourceIds
        }
        if (!openAiEnabled && customSources.isEmpty()) {
            availableSkillsJob?.cancel()
            availableSkillsJob = null
            mutableState.update {
                it.copy(
                    availableSkills = emptyList(),
                    availableSkillsLoaded = true,
                    isAvailableSkillsLoading = false,
                    availableSkillsError = null,
                )
            }
            return
        }
        val controller = serviceController ?: return
        val current = mutableState.value
        if (!forceReload && (current.availableSkillsLoaded || availableSkillsJob?.isActive == true)) return
        if (forceReload) availableSkillsJob?.cancel()
        mutableState.update { it.copy(isAvailableSkillsLoading = true, availableSkillsError = null) }
        availableSkillsJob = viewModelScope.launch {
            val installed = mutableState.value.skills.map(AgentSkill::name).toSet()
            val (openAiResult, customResults) = coroutineScope {
                val openAi = async {
                    if (openAiEnabled) runCatching {
                        controller.listAvailableSkills(installed, forceReload)
                    } else null
                }
                val custom = customSources.map { source ->
                    async { source to runCatching { controller.discoverGitHubSkills(source.url) } }
                }
                openAi.await() to custom.map { it.await() }
            }
            ensureActive()
            (openAiResult?.exceptionOrNull() as? CancellationException)?.let { throw it }
            customResults.forEach { (_, result) ->
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            }
            if (serviceController !== controller) return@launch
            val errors = buildList {
                openAiResult?.exceptionOrNull()?.message?.let(::add)
                openAiResult?.getOrNull()?.errors?.let(::addAll)
                customResults.forEach { (source, result) ->
                    result.exceptionOrNull()?.message?.let { add("${source.url}: $it") }
                }
            }
            val packages = buildList {
                openAiResult?.getOrNull()?.skills?.let(::addAll)
                customResults.forEach { (_, result) -> result.getOrNull()?.let(::addAll) }
            }.filterNot { it.name in installed }.distinctBy(AgentSkillPackage::name)
            val refreshAfterCache = !forceReload &&
                openAiResult?.getOrNull()?.freshness == AgentCatalogFreshness.STALE_CACHE
            mutableState.update {
                it.copy(
                    availableSkills = packages,
                    availableSkillsLoaded = true,
                    isAvailableSkillsLoading = refreshAfterCache,
                    availableSkillsError = errors.distinct().joinToString("\n").ifBlank { null },
                )
            }
            if (refreshAfterCache) {
                availableSkillsJob = null
                loadAvailableSkills(forceReload = true)
            }
        }
    }

    private fun loadPluginCatalog(forceReload: Boolean, allowFollowUp: Boolean = true) {
        val current = mutableState.value
        val controller = serviceController
        if (controller == null || !current.isAuthenticated) {
            val reconnecting = uiPreferences.hadAuthenticatedSession
            mutableState.update {
                it.copy(
                    pluginCatalogStatus = if (reconnecting) {
                        PluginCatalogStatus.CONNECTING
                    } else {
                        PluginCatalogStatus.ERROR
                    },
                    pluginCatalogError = if (reconnecting) null else "Sign in to load plugins.",
                )
            }
            if (reconnecting && !current.isAuthenticationInProgress) authenticate()
            return
        }
        if (pluginsJob?.isActive == true) {
            if (forceReload) pluginRefreshPending = true
            return
        }
        if (!forceReload && current.pluginCatalogStatus == PluginCatalogStatus.LIVE) return

        mutableState.update {
            it.copy(pluginCatalogStatus = PluginCatalogStatus.LOADING, pluginCatalogError = null)
        }
        val workingDirectory = container.platform.activeWorkspacePath()
        pluginsJob = viewModelScope.launch {
            val sourceErrors = reconcileEnabledPluginSources(controller)
            val installedResult = runCatching {
                controller.listInstalledPlugins(workingDirectory, forceRefresh = forceReload)
            }
            val availableResult = runCatching {
                controller.listAvailablePlugins(workingDirectory, forceRefresh = forceReload)
            }
            ensureActive()
            if (serviceController !== controller) return@launch

            val before = mutableState.value
            val installedCatalog = installedResult.getOrNull()
            val availableCatalog = availableResult.getOrNull()
            val installedCandidates = installedCatalog?.plugins ?: before.installedPlugins
            val availableCandidates = availableCatalog?.plugins ?: before.availablePlugins
            val merged = (availableCandidates + installedCandidates)
                .associateBy { it.reference.id }
                .values
            registerDiscoveredPluginSources(merged.toList())
            val sourceSelection = mutableState.value
            val installedIds = buildSet {
                installedCandidates.mapTo(this) { it.reference.id }
                merged.filter(AgentPluginSummary::installed).mapTo(this) { it.reference.id }
            }
            val installedPlugins = merged.filter { it.reference.id in installedIds }
            val availablePlugins = merged.filter { plugin ->
                plugin.reference.id !in installedIds &&
                    sourceSelection.isPluginMarketplaceEnabled(plugin.reference.marketplaceName)
            }

            val errors = buildList {
                addAll(sourceErrors)
                addAll(installedCatalog?.errors.orEmpty())
                addAll(availableCatalog?.errors.orEmpty())
                installedResult.exceptionOrNull()?.let {
                    add(it.message?.take(300) ?: "Installed plugins could not be refreshed")
                }
                availableResult.exceptionOrNull()?.let {
                    add(it.message?.take(300) ?: "Available plugins could not be refreshed")
                }
            }.distinct()
            val live = sourceErrors.isEmpty() &&
                installedCatalog?.freshness == AgentCatalogFreshness.LIVE &&
                installedCatalog.errors.isEmpty() &&
                availableCatalog?.freshness == AgentCatalogFreshness.LIVE &&
                availableCatalog.errors.isEmpty()
            val status = when {
                live && (installedPlugins.isNotEmpty() || availablePlugins.isNotEmpty() || errors.isEmpty()) -> {
                    PluginCatalogStatus.LIVE
                }
                installedPlugins.isNotEmpty() || availablePlugins.isNotEmpty() -> PluginCatalogStatus.STALE
                else -> PluginCatalogStatus.ERROR
            }
            val confirmedAvailableIds = availableCatalog
                ?.takeIf { it.freshness == AgentCatalogFreshness.LIVE && it.errors.isEmpty() }
                ?.plugins
                ?.filter(AgentPluginSummary::available)
                ?.mapTo(mutableSetOf()) { it.reference.id }
                .orEmpty()
            mutableState.update {
                it.copy(
                    installedPlugins = installedPlugins,
                    availablePlugins = availablePlugins,
                    pluginCatalogStatus = status,
                    pluginCatalogError = errors.joinToString("\n").ifBlank { null },
                    unavailablePluginIds = it.unavailablePluginIds - confirmedAvailableIds,
                )
            }
            if (live) reconcileStoredPluginSetups(mutableState.value.connectors, installedIds)

            val cached = !forceReload && listOfNotNull(installedCatalog, availableCatalog).any {
                it.freshness != AgentCatalogFreshness.LIVE
            }
            val followUp = allowFollowUp && (pluginRefreshPending || cached)
            pluginRefreshPending = false
            pluginsJob = null
            if (followUp) loadPluginCatalog(forceReload = true, allowFollowUp = false)
        }
    }

    private suspend fun reconcileEnabledPluginSources(controller: CodexSessionController): List<String> {
        val current = mutableState.value
        val sources = buildList {
            if (CODEX_MOBILE_PLUGIN_SOURCE_ID in current.enabledExtensionSourceIds) {
                add(CODEX_MOBILE_PLUGIN_SOURCE_ID to CODEX_MOBILE_PLUGIN_SOURCE_URL)
            }
            current.customExtensionSources.filter {
                it.supportsPlugins && it.id in current.enabledExtensionSourceIds
            }.forEach { add(it.id to it.url) }
        }
        return buildList {
            sources.filterNot { it.first in reconciledPluginSourceIds }.forEach { (id, url) ->
                runCatching { controller.addPluginMarketplace(url, reuseSnapshot = true) }
                    .onSuccess { reconciledPluginSourceIds += id }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        add(error.message?.take(300) ?: "Plugin source could not be restored")
                    }
            }
        }
    }

    private fun registerDiscoveredPluginSources(plugins: List<AgentPluginSummary>) {
        val mappedMarketplaceNames = mutableState.value.customExtensionSources
            .mapNotNull(CustomExtensionSource::marketplaceName)
            .map(::canonicalPluginSourceId)
            .toSet()
        val discovered = plugins.map { canonicalPluginSourceId(it.reference.marketplaceName) }
            .filter(String::isNotBlank)
            .filterNot { it in mappedMarketplaceNames }
            .toSet()
        if (discovered.isEmpty()) return
        mutableState.update {
            val newIds = discovered - it.knownExtensionSourceIds
            it.copy(
                knownExtensionSourceIds = it.knownExtensionSourceIds + discovered,
                enabledExtensionSourceIds = it.enabledExtensionSourceIds + (newIds - OPENAI_PLUGIN_SOURCE_ID),
            )
        }
        persistExtensionSourceSelection()
    }

    private fun persistExtensionSourceSelection() {
        val current = mutableState.value
        uiPreferences.saveExtensionSourceSelection(
            current.knownExtensionSourceIds,
            current.enabledExtensionSourceIds,
            current.customExtensionSources,
        )
    }

    private suspend fun refreshConnectors(
        controller: CodexSessionController,
        forceReload: Boolean,
    ): List<AgentConnector>? = connectorRefreshMutex.withLock {
        val refreshedConnectors = runCatching { controller.listConnectors(forceReload) }.getOrNull()
        if (refreshedConnectors != null) {
            mutableState.update { it.copy(connectors = refreshedConnectors) }
            reconcileStoredPluginSetups(refreshedConnectors)
        }
        refreshedConnectors
    }

    private fun setPendingPluginSetup(pluginId: String, connectorIds: Set<String>) {
        val normalized = connectorIds.filter(String::isNotBlank).toSet()
        val updated = mutableState.value.pendingPluginSetups.toMutableMap().apply {
            if (normalized.isEmpty()) remove(pluginId) else put(pluginId, normalized)
        }.toMap()
        mutableState.update { it.copy(pendingPluginSetups = updated) }
        uiPreferences.savePendingPluginSetups(updated)
    }

    private fun reconcileStoredPluginSetups(
        connectors: List<AgentConnector>,
        installedPluginIds: Set<String>? = null,
    ) {
        val current = mutableState.value.pendingPluginSetups
        val reconciled = reconcilePendingPluginSetups(current, connectors, installedPluginIds)
        if (reconciled == current) return
        mutableState.update { it.copy(pendingPluginSetups = reconciled) }
        uiPreferences.savePendingPluginSetups(reconciled)
    }

    private fun showExtensionNotice(message: String, isError: Boolean = false) {
        val notice = ExtensionNotice(message, isError)
        extensionNoticeJob?.cancel()
        mutableState.update { it.copy(extensionNotice = notice) }
        extensionNoticeJob = viewModelScope.launch {
            delay(EXTENSION_NOTICE_DURATION_MILLIS)
            mutableState.update { state -> state.copy(extensionNotice = state.extensionNotice.afterExpiry(notice)) }
            extensionNoticeJob = null
        }
    }

    private fun cancelExtensionNotice() {
        extensionNoticeJob?.cancel()
        extensionNoticeJob = null
    }

    private fun extensionMutation(operationId: String, message: String, block: suspend () -> Unit) {
        mutableState.update {
            it.copy(
                isExtensionMutationLoading = true,
                extensionOperationId = operationId,
                extensionActionError = null,
            )
        }
        viewModelScope.launch {
            try {
                block()
                mutableState.update {
                    it.copy(isExtensionMutationLoading = false, extensionOperationId = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                extensionFailure(error, message)
            }
        }
    }

    private fun extensionFailure(error: Throwable, fallback: String = "Extension request failed") {
        val unavailable = error as? AgentPluginUnavailableException
        mutableState.update {
            val message = error.message?.take(300) ?: fallback
            val operationId = it.extensionOperationId
                ?: unavailable?.let { failure -> "plugin:${failure.pluginId}" }
                ?: "extension"
            it.copy(
                isExtensionMutationLoading = false,
                extensionOperationId = null,
                extensionActionError = ExtensionActionError(operationId, message),
                unavailablePluginIds = unavailable?.let { failure ->
                    it.unavailablePluginIds + failure.pluginId
                } ?: it.unavailablePluginIds,
            )
        }
        if (unavailable != null) loadPluginCatalog(forceReload = true)
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

    private fun beginOnUseAuthentication(state: AppUiState): Boolean {
        val pendingPlugin = state.selectedInvocations.filterIsInstance<AgentInvocation.Plugin>()
            .mapNotNull { invocation -> state.plugins.firstOrNull { it.reference.uri == invocation.uri } }
            .firstOrNull { it.reference.id in state.pendingPluginSetups }
        if (pendingPlugin != null) {
            connectPlugin(pendingPlugin.reference)
            mutableState.update { it.copy(statusMessage = "Connect the selected plugin to continue") }
            return true
        }
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
        uiPreferences.saveSelection(
            current.selectedModel,
            current.selectedEffort,
            current.selectedSpeedTier,
            current.approvalPreset,
        )
    }

    private fun persistPinnedConversations(ids: Set<String>) {
        uiPreferences.savePinnedConversationIds(ids)
    }

    private fun applySessionState(
        session: CodexSessionState,
        notificationVisible: Boolean,
    ) {
        if (session.isAuthenticated) uiPreferences.setHadAuthenticatedSession(true)
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
                    reasoning = session.streamedReasoning,
                    plan = session.streamedPlan,
                    planProgress = session.planProgress,
                    hookActivities = session.hookActivities,
                    isStreaming = session.isTurnActive,
                    exitCode = session.shellExitCode,
                )
            } ?: current.messages
            current.copy(
                statusMessage = session.statusMessage,
                streamedText = session.streamedText,
                streamedReasoning = session.streamedReasoning,
                streamedPlan = session.streamedPlan,
                planProgress = session.planProgress,
                hookActivities = session.hookActivities,
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

    private fun releaseServiceBinding() {
        serviceConnection.unbind()
        serviceEnded()
    }

    private fun serviceEnded() {
        serviceStartPending = false
        val recoverAuthentication = authenticationHandoffPending()
        pendingConversationId = null
        serviceStateJob?.cancel()
        serviceStateJob = null
        cancelServiceRequests()
        serviceController = null
        notificationsEnabled = null
        signOutAction = null
        signOutPending = false
        serviceInstanceId = null
        reconciledPluginSourceIds = emptySet()
        pluginRefreshPending = false
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
                skillsLoaded = false,
                availableSkillsLoaded = false,
                pluginCatalogStatus = if (it.plugins.isEmpty()) {
                    PluginCatalogStatus.NOT_LOADED
                } else {
                    PluginCatalogStatus.STALE
                },
                isSkillsLoading = false,
                isAvailableSkillsLoading = false,
                isExtensionSourceLoading = false,
                isExtensionMutationLoading = false,
                extensionOperationId = null,
                isConversationLoading = false,
                skillsError = null,
                availableSkillsError = null,
                pluginCatalogError = if (it.plugins.isEmpty()) null else "Codex disconnected; showing saved plugins.",
                extensionActionError = null,
            )
        }
        if (recoverAuthentication) {
            viewModelScope.launch {
                delay(AUTHENTICATION_RECOVERY_DELAY_MILLIS)
                if (serviceController == null && authenticationHandoffPending()) authenticate()
            }
        }
    }

    private fun cancelServiceRequests() {
        skillsJob?.cancel()
        availableSkillsJob?.cancel()
        pluginsJob?.cancel()
        connectorAuthenticationJob?.cancel()
        connectorRefreshJob?.cancel()
        extensionSourceJob?.cancel()
        skillsJob = null
        availableSkillsJob = null
        pluginsJob = null
        connectorAuthenticationJob = null
        connectorRefreshJob = null
        extensionSourceJob = null
    }

    private fun authenticationHandoffPending(): Boolean = uiPreferences.authenticationHandoffPending()

    private fun setAuthenticationHandoffPending(pending: Boolean) {
        uiPreferences.setAuthenticationHandoffPending(pending)
    }

    private companion object {
        const val MAX_CONVERSATION_TITLE_LENGTH = 80
        const val EXISTING_SERVICE_BIND_TIMEOUT_MILLIS = 1_000L
        const val AUTHENTICATION_RECOVERY_DELAY_MILLIS = 150L
        const val CONNECTOR_UPDATE_WAIT_MILLIS = 1_500L
        const val EXTENSION_NOTICE_DURATION_MILLIS = 4_000L
    }
}

internal enum class SendMessageOutcome { HANDLED, WORKSPACE_REQUIRED }

private fun AppUiState.isPluginMarketplaceEnabled(marketplaceName: String): Boolean {
    val canonical = canonicalPluginSourceId(marketplaceName)
    return ExtensionSourceSelection(
        knownExtensionSourceIds,
        enabledExtensionSourceIds,
        customExtensionSources,
    ).enabledMarketplaceNames().any {
        it == marketplaceName || canonicalPluginSourceId(it) == canonical
    }
}
