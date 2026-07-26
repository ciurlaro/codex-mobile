package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.app.lifecycle.CodexMobileApplication
import io.github.ciurlaro.codexmobile.app.persistence.AppPreferencesStore
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.withoutActiveInvocationToken
import io.github.ciurlaro.codexmobile.app.presentation.invocation.withRecentInvocation
import io.github.ciurlaro.codexmobile.app.presentation.mapper.toChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionActionError
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionFilter
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionRemoval
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSection
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_URL
import io.github.ciurlaro.codexmobile.app.presentation.model.OPENAI_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.canonicalPluginSourceId
import io.github.ciurlaro.codexmobile.app.presentation.model.initialPluginSourceSelection
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
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentInvocation
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val container = (application as CodexMobileApplication).container
    private val uiPreferences = AppPreferencesStore(appContext)
    private val initialPluginSources = initialPluginSourceSelection(
        savedKnownIds = uiPreferences.savedKnownPluginSourceIds,
        savedEnabledIds = uiPreferences.savedEnabledPluginSourceIds,
        appWasUpgraded = uiPreferences.appWasUpgraded,
    )
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
            knownPluginSourceIds = initialPluginSources.knownIds,
            enabledPluginSourceIds = initialPluginSources.enabledIds,
        ),
    )
    private var serviceController: CodexSessionController? = null
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
    private var installedPluginsJob: Job? = null
    private var availablePluginsJob: Job? = null
    private var skillSourceJob: Job? = null
    private var pluginSourceJob: Job? = null
    private var codexMobilePluginSourceAdded = uiPreferences.codexMobilePluginSourceAdded
    private var integrationsLoaded = false
    private val pendingConnectorAuthentications = ArrayDeque<AgentConnector>()
    internal var serviceInstanceId: String? = null
        private set

    private val serviceConnection = CodexServiceConnection(
        context = appContext,
        onConnected = ::serviceConnected,
        onEnded = ::serviceEnded,
    )

    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    init {
        uiPreferences.savePluginSourceSelection(initialPluginSources.knownIds, initialPluginSources.enabledIds)
        viewModelScope.launch {
            container.backgroundSessions.failure.collect { failure ->
                failure?.let { message ->
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
        val authorization = container.backgroundSessions.authorizeStart()
        try {
            appContext.startForegroundService(
                CodexForegroundService.startIntent(appContext, authorization, authenticate = true),
            )
            serviceConnection.bind(Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
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

    fun resumeAfterProviderPackageUpdate() {
        val completion = container.platform.consumeProviderPackageCompletion() ?: return
        mutableState.update {
            it.copy(
                screen = AppScreen.EXTENSIONS,
                extensionsReturnScreen = AppScreen.SETTINGS,
                extensionSection = ExtensionSection.INSTALLED,
                extensionFilter = ExtensionFilter.PLUGINS,
                extensionSearch = "",
                extensionSourcesOpen = false,
                selectedPlugin = null,
                pendingExtensionRemoval = null,
                installedPluginsLoaded = false,
                availablePluginsLoaded = false,
                extensionNotice = ExtensionNotice(completion.message, isError = !completion.successful),
                statusMessage = completion.message,
            )
        }
        if (uiPreferences.hadAuthenticatedSession) authenticate()
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
        val workingDirectory = container.platform.activeWorkspacePath()
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

    fun openExtensions(filter: ExtensionFilter, returnScreen: AppScreen) {
        mutableState.update {
            it.copy(
                screen = AppScreen.EXTENSIONS,
                extensionsReturnScreen = returnScreen,
                extensionFilter = filter,
                extensionSection = ExtensionSection.INSTALLED,
                extensionSearch = "",
                extensionSourcesOpen = false,
                extensionNotice = null,
                isHistoryOpen = false,
                activeSelector = null,
            )
        }
        loadCurrentExtensions(forceReload = false)
    }

    fun closeExtensions() {
        skillSourceJob?.cancel()
        mutableState.update {
            it.copy(
                screen = it.extensionsReturnScreen,
                selectedSkill = null,
                selectedSkillPackage = null,
                selectedPlugin = null,
                pendingExtensionRemoval = null,
                githubSkillCandidates = emptyList(),
                githubSkillError = null,
                isGitHubSkillLoading = false,
                extensionActionError = null,
                extensionNotice = null,
                extensionSourcesOpen = false,
                skillSourceChunks = emptyList(),
                skillSourceNextOffset = null,
            )
        }
    }

    fun refreshExtensions() {
        mutableState.update {
            it.copy(
                extensionActionError = null,
                unavailablePluginIds = emptySet(),
                extensionNotice = null,
            )
        }
        loadCurrentExtensions(forceReload = true)
    }

    fun selectExtensionFilter(filter: ExtensionFilter) {
        mutableState.update {
            it.copy(
                extensionFilter = filter,
                selectedSkill = null,
                selectedSkillPackage = null,
                selectedPlugin = null,
                extensionSearch = "",
                extensionActionError = null,
            )
        }
        loadCurrentExtensions(forceReload = false)
    }

    fun selectExtensionSection(section: ExtensionSection) {
        mutableState.update {
            it.copy(
                extensionSection = section,
                selectedSkill = null,
                selectedSkillPackage = null,
                selectedPlugin = null,
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
        mutableState.update { it.copy(extensionSourcesOpen = true, extensionNotice = null) }
    }

    fun closeExtensionSources() {
        mutableState.update { it.copy(extensionSourcesOpen = false) }
        if (mutableState.value.extensionSection == ExtensionSection.DISCOVER) loadCurrentExtensions(forceReload = false)
    }

    fun togglePluginSource(sourceId: String, enabled: Boolean) {
        val normalized = canonicalPluginSourceId(sourceId)
        val current = mutableState.value
        if (normalized !in current.knownPluginSourceIds) return
        availablePluginsJob?.cancel()
        availableSkillsJob?.cancel()
        mutableState.update {
            val enabledIds = if (enabled) it.enabledPluginSourceIds + normalized
            else it.enabledPluginSourceIds - normalized
            it.copy(
                enabledPluginSourceIds = enabledIds,
                availablePlugins = it.availablePlugins.filter { plugin ->
                    canonicalPluginSourceId(plugin.reference.marketplaceName) in enabledIds
                },
                availableSkills = if (OPENAI_PLUGIN_SOURCE_ID in enabledIds) it.availableSkills else emptyList(),
                availablePluginsLoaded = false,
                availableSkillsLoaded = false,
                availablePluginsError = null,
                availableSkillsError = null,
            )
        }
        persistPluginSourceSelection()
    }

    fun closePluginDetails() {
        mutableState.update { it.copy(selectedPlugin = null, extensionActionError = null) }
    }

    fun closeSkillDetails() {
        skillSourceJob?.cancel()
        mutableState.update {
            it.copy(
                selectedSkill = null,
                selectedSkillPackage = null,
                skillSourceChunks = emptyList(),
                skillSourceNextOffset = null,
                skillSourceTotalBytes = 0,
                isSkillSourceLoading = false,
                skillSourceError = null,
                extensionActionError = null,
            )
        }
    }

    fun openSkill(skill: AgentSkill) {
        mutableState.update {
            it.copy(
                selectedSkill = skill,
                selectedSkillPackage = null,
                skillSourceChunks = emptyList(),
                skillSourceNextOffset = 0,
                skillSourceTotalBytes = 0,
                skillSourceError = null,
                extensionActionError = null,
            )
        }
        loadMoreSkillSource()
    }

    fun openSkillPackage(packageInfo: AgentSkillPackage) {
        mutableState.update {
            it.copy(
                selectedSkill = null,
                selectedSkillPackage = packageInfo,
                githubSkillCandidates = emptyList(),
                githubSkillError = null,
                isGitHubSkillLoading = false,
                skillSourceChunks = emptyList(),
                skillSourceNextOffset = 0,
                skillSourceTotalBytes = 0,
                skillSourceError = null,
                extensionActionError = null,
            )
        }
        loadMoreSkillSource()
    }

    fun openGitHubSkill(url: String) {
        val controller = serviceController ?: return
        if (url.isBlank()) return
        mutableState.update {
            it.copy(
                githubSkillCandidates = emptyList(),
                githubSkillError = null,
                isGitHubSkillLoading = true,
            )
        }
        skillSourceJob?.cancel()
        skillSourceJob = viewModelScope.launch {
            runCatching { controller.discoverGitHubSkills(url) }
                .onSuccess { packages ->
                    skillSourceJob = null
                    if (packages.size == 1) {
                        openSkillPackage(packages.single())
                    } else {
                        mutableState.update {
                            it.copy(
                                githubSkillCandidates = packages,
                                isGitHubSkillLoading = false,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(
                            isGitHubSkillLoading = false,
                            githubSkillError = error.message?.take(300) ?: "GitHub skill could not be opened",
                        )
                    }
                }
        }
    }

    fun selectGitHubSkill(packageInfo: AgentSkillPackage) = openSkillPackage(packageInfo)

    fun dismissGitHubSkillImport() {
        if (mutableState.value.isGitHubSkillLoading) skillSourceJob?.cancel()
        mutableState.update {
            it.copy(
                githubSkillCandidates = emptyList(),
                githubSkillError = null,
                isGitHubSkillLoading = false,
            )
        }
    }

    fun addPluginSource(url: String) {
        val controller = serviceController ?: run {
            mutableState.update { it.copy(pluginSourceError = "Codex is not ready") }
            return
        }
        if (url.isBlank() || pluginSourceJob?.isActive == true) return
        mutableState.update { it.copy(pluginSourceError = null, isPluginSourceLoading = true) }
        pluginSourceJob = viewModelScope.launch {
            runCatching { controller.addPluginMarketplace(url) }
                .onSuccess {
                    pluginSourceJob = null
                    mutableState.update { it.copy(isPluginSourceLoading = false) }
                    loadAvailablePlugins(forceReload = true)
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    pluginSourceJob = null
                    mutableState.update {
                        it.copy(
                            isPluginSourceLoading = false,
                            pluginSourceError = error.message?.take(300) ?: "Plugin source could not be added",
                        )
                    }
                }
        }
    }

    fun dismissPluginSource() {
        pluginSourceJob?.cancel()
        pluginSourceJob = null
        mutableState.update { it.copy(pluginSourceError = null, isPluginSourceLoading = false) }
    }

    fun loadMoreSkillSource() {
        val controller = serviceController ?: return
        val current = mutableState.value
        val skill = current.selectedSkill
        val packageInfo = current.selectedSkillPackage
        if (skill == null && packageInfo == null) return
        val offset = current.skillSourceNextOffset ?: return
        if (skillSourceJob?.isActive == true) return
        mutableState.update { it.copy(isSkillSourceLoading = true, skillSourceError = null) }
        skillSourceJob = viewModelScope.launch {
            runCatching {
                if (skill != null) controller.readSkill(skill.path, offset)
                else controller.readSkillPackage(requireNotNull(packageInfo), offset)
            }
                .onSuccess { chunk ->
                    mutableState.update {
                        val stillSelected = if (skill != null) it.selectedSkill?.path == skill.path
                        else it.selectedSkillPackage?.id == packageInfo?.id
                        if (!stillSelected) it else it.copy(
                            skillSourceChunks = it.skillSourceChunks + chunk.content,
                            skillSourceNextOffset = chunk.nextOffset,
                            skillSourceTotalBytes = chunk.totalBytes,
                            isSkillSourceLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(
                            isSkillSourceLoading = false,
                            skillSourceError = error.message?.take(300) ?: "Skill source could not be read",
                        )
                    }
                }
        }
    }

    fun openPlugin(plugin: AgentPluginReference) {
        val controller = serviceController ?: return
        mutableState.update {
            it.copy(
                isPluginDetailLoading = true,
                extensionOperationId = "plugin:${plugin.id}",
                extensionActionError = null,
            )
        }
        viewModelScope.launch {
            runCatching { controller.readPlugin(plugin) }
                .onSuccess { detail ->
                    mutableState.update {
                        it.copy(
                            selectedPlugin = detail,
                            isPluginDetailLoading = false,
                            extensionOperationId = null,
                        )
                    }
                }
                .onFailure { error -> extensionFailure(error) }
        }
    }

    fun toggleSkill(path: String, enabled: Boolean) = extensionMutation(
        "skill:$path",
        "Skill could not be updated",
    ) {
        serviceController?.setSkillEnabled(path, enabled)
        loadSkills(forceReload = true)
    }

    fun installSkill(packageInfo: AgentSkillPackage) = extensionMutation(
        "skill:${packageInfo.id}",
        "Skill could not be installed",
    ) {
        serviceController?.installSkill(packageInfo)
        mutableState.update {
            it.copy(
                availableSkills = it.availableSkills.filterNot { candidate -> candidate.id == packageInfo.id },
                selectedSkillPackage = null,
                extensionSection = ExtensionSection.INSTALLED,
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
        val result = serviceController?.installPlugin(plugin) ?: return@extensionMutation
        if (result.restartRequired) {
            mutableState.update {
                it.copy(
                    statusMessage = result.message ?: "Restart Codex Mobile to finish installing the provider",
                    selectedPlugin = null,
                    providerSettings = container.platform.providerSettings(),
                )
            }
            return@extensionMutation
        }
        resetChat()
        mutableState.update {
            it.copy(
                selectedPlugin = null,
                availablePlugins = it.availablePlugins.filterNot { candidate -> candidate.reference.id == plugin.id },
                unavailablePluginIds = it.unavailablePluginIds - plugin.id,
                extensionActionError = null,
                extensionSection = ExtensionSection.INSTALLED,
            )
        }
        loadInstalledPlugins(forceReload = true)
        if (result.authPolicy == AgentPluginAuthPolicy.ON_INSTALL) {
            enqueueConnectorAuthentication(result.connectorsNeedingAuthentication)
        }
    }

    private fun uninstallPlugin(plugin: AgentPluginReference) = extensionMutation(
        "plugin:${plugin.id}",
        "Plugin could not be removed",
    ) {
        val result = serviceController?.uninstallPlugin(plugin) ?: return@extensionMutation
        resetChat()
        mutableState.update {
            it.copy(
                selectedPlugin = null,
                statusMessage = result.message ?: if (result.completed) "Plugin removed" else it.statusMessage,
                installedPlugins = if (result.restartRequired || result.completed) {
                    it.installedPlugins.filterNot { candidate -> candidate.reference.id == plugin.id }
                } else {
                    it.installedPlugins
                },
                availablePluginsLoaded = false,
                providerSettings = container.platform.providerSettings(),
            )
        }
        loadInstalledPlugins(forceReload = true)
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
                            selectedSkill = null,
                            skills = it.skills.filterNot { candidate -> candidate.path == removal.skill.path },
                        )
                    }
                    mutableState.update { it.copy(availableSkillsLoaded = false) }
                    loadAvailableSkills(forceReload = false)
                }
            }
            is ExtensionRemoval.Plugin -> {
                mutableState.update { it.copy(pendingExtensionRemoval = null) }
                uninstallPlugin(removal.plugin)
            }
            null -> Unit
        }
    }

    fun togglePlugin(pluginId: String, enabled: Boolean) = extensionMutation(
        "plugin:$pluginId",
        "Plugin could not be updated",
    ) {
        serviceController?.setPluginEnabled(pluginId, enabled)
        resetChat()
        loadInstalledPlugins(forceReload = true)
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
                .onFailure { error ->
                    mutableState.update {
                        it.copy(statusMessage = error.message?.take(300) ?: "Integration could not be connected")
                    }
                }
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

    fun refreshIntegrations() {
        integrationsLoaded = true
        serviceController?.let { controller ->
            viewModelScope.launch { refreshConnectors(controller, forceReload = false) }
        }
    }

    fun openProviderSettings(pluginId: String) {
        val entry = mutableState.value.providerSettings.singleOrNull { it.pluginId == pluginId }
        if (entry?.activityClassName == null && entry?.removalNeedsRetry == true) {
            viewModelScope.launch {
                runCatching { container.platform.finishProviderRemoval(pluginId) }
                    .onSuccess {
                        mutableState.update { state ->
                            state.copy(statusMessage = "Restart Codex Mobile to verify provider removal")
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

    fun stopBackgroundWork() {
        setAuthenticationHandoffPending(false)
        runCatching { appContext.startService(CodexForegroundService.stopIntent(appContext)) }
            .onFailure {
                mutableState.update { state -> state.copy(statusMessage = "Background work could not be stopped") }
            }
    }

    fun signOut() {
        setAuthenticationHandoffPending(false)
        uiPreferences.setHadAuthenticatedSession(false)
        mutableState.update {
            it.copy(statusMessage = "Signing out…", signInUrl = null)
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
                        installedPluginsLoaded = false,
                        availablePluginsLoaded = false,
                    )
                }
            }
            .onFailure { mutableState.update { state -> state.copy(statusMessage = "Workspace selection failed") } }
    }

    fun clearWorkspace() {
        runCatching { container.platform.clearWorkspace() }
            .onSuccess {
                mutableState.update {
                    it.copy(
                        statusMessage = "Workspace cleared",
                        workspacePath = null,
                        skills = emptyList(),
                        availableSkills = emptyList(),
                        installedPlugins = emptyList(),
                        availablePlugins = emptyList(),
                        unavailablePluginIds = emptySet(),
                        extensionActionError = null,
                        skillsLoaded = false,
                        availableSkillsLoaded = false,
                        installedPluginsLoaded = false,
                        availablePluginsLoaded = false,
                    )
                }
            }
            .onFailure { mutableState.update { it.copy(statusMessage = "Workspace could not be cleared") } }
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
        installedPluginsJob?.cancel()
        availablePluginsJob?.cancel()
        skillSourceJob?.cancel()
        serviceConnection.unbind()
        serviceController = null
        notificationsEnabled = null
        signOutAction = null
        signOutPending = false
        serviceInstanceId = null
    }

    private fun serviceConnected(binder: CodexForegroundService.LocalBinder) {
        serviceController = binder.controller
        notificationsEnabled = binder::notificationsEnabled
        signOutAction = binder::signOut
        serviceInstanceId = binder.serviceInstanceId
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
                    if (mutableState.value.installedPluginsLoaded) loadInstalledPlugins(forceReload = true)
                    mutableState.update { it.copy(providerSettings = container.platform.providerSettings()) }
                }
                if (session.connectorsRevision != connectorsRevision) {
                    connectorsRevision = session.connectorsRevision
                    if (integrationsLoaded) launch { refreshConnectors(binder.controller, forceReload = true) }
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
        loadInstalledPlugins(forceReload = false)
        if (mutableState.value.screen == AppScreen.EXTENSIONS) loadCurrentExtensions(forceReload = false)
    }

    private fun loadCurrentExtensions(forceReload: Boolean) {
        val current = mutableState.value
        when (current.extensionSection) {
            ExtensionSection.INSTALLED -> {
                if (current.extensionFilter != ExtensionFilter.PLUGINS) loadSkills(forceReload)
                if (current.extensionFilter != ExtensionFilter.SKILLS) loadInstalledPlugins(forceReload)
            }
            ExtensionSection.DISCOVER -> {
                if (current.extensionFilter != ExtensionFilter.PLUGINS) loadAvailableSkills(forceReload)
                if (current.extensionFilter != ExtensionFilter.SKILLS) loadAvailablePlugins(forceReload)
            }
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
        if (OPENAI_PLUGIN_SOURCE_ID !in mutableState.value.enabledPluginSourceIds) {
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
            runCatching { controller.listAvailableSkills(installed, forceReload) }
                .onSuccess { catalog ->
                    if (serviceController !== controller) return@onSuccess
                    mutableState.update {
                        it.copy(
                            availableSkills = catalog.skills,
                            availableSkillsLoaded = true,
                            isAvailableSkillsLoading = false,
                            availableSkillsError = catalog.errors.distinct().joinToString("\n").ifBlank { null },
                        )
                    }
                    if (!forceReload && catalog.freshness == AgentCatalogFreshness.STALE_CACHE) {
                        availableSkillsJob = null
                        loadAvailableSkills(forceReload = true)
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (serviceController !== controller) return@onFailure
                    mutableState.update {
                        it.copy(
                            availableSkillsLoaded = true,
                            isAvailableSkillsLoading = false,
                            availableSkillsError = error.message?.take(300) ?: "Available skills could not be loaded",
                        )
                    }
                }
        }
    }

    private fun loadInstalledPlugins(forceReload: Boolean) {
        val controller = serviceController ?: return
        val current = mutableState.value
        if (!forceReload && (current.installedPluginsLoaded || installedPluginsJob?.isActive == true)) return
        if (forceReload) installedPluginsJob?.cancel()
        val workingDirectory = container.platform.activeWorkspacePath() ?: return
        mutableState.update { it.copy(isInstalledPluginsLoading = true, installedPluginsError = null) }
        installedPluginsJob = viewModelScope.launch {
            runCatching { controller.listInstalledPlugins(workingDirectory) }
                .onSuccess { catalog ->
                    if (serviceController !== controller) return@onSuccess
                    mutableState.update {
                        it.copy(
                            installedPlugins = catalog.plugins,
                            installedPluginsLoaded = true,
                            isInstalledPluginsLoading = false,
                            installedPluginsError = catalog.errors.distinct().joinToString("\n").ifBlank { null },
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (serviceController !== controller) return@onFailure
                    mutableState.update {
                        it.copy(
                            installedPluginsLoaded = true,
                            isInstalledPluginsLoading = false,
                            installedPluginsError = error.message?.take(300) ?: "Installed plugins could not be loaded",
                        )
                    }
                }
        }
    }

    private fun loadAvailablePlugins(forceReload: Boolean) {
        val controller = serviceController ?: return
        val current = mutableState.value
        if (availablePluginsJob?.isActive == true || (!forceReload && current.availablePluginsLoaded)) return
        val workingDirectory = container.platform.activeWorkspacePath() ?: return
        mutableState.update { it.copy(isAvailablePluginsLoading = true, availablePluginsError = null) }
        availablePluginsJob = viewModelScope.launch {
            runCatching {
                var sourceAdded = false
                val bootstrapError = runCatching {
                    sourceAdded = ensureCodexMobilePluginSource(controller)
                }.exceptionOrNull()
                controller.listAvailablePlugins(workingDirectory, forceReload || sourceAdded).let { catalog ->
                    catalog.copy(errors = catalog.errors + listOfNotNull(bootstrapError?.message))
                }
            }
                .onSuccess { catalog ->
                    if (serviceController !== controller) return@onSuccess
                    registerDiscoveredPluginSources(catalog.plugins)
                    val refreshAfterCache = !forceReload && catalog.freshness != AgentCatalogFreshness.LIVE
                    mutableState.update {
                        val installedIds = it.installedPlugins.map { plugin -> plugin.reference.id }.toSet()
                        it.copy(
                            availablePlugins = catalog.plugins.filter { plugin ->
                                plugin.reference.id !in installedIds &&
                                    canonicalPluginSourceId(plugin.reference.marketplaceName) in it.enabledPluginSourceIds
                            },
                            availablePluginsLoaded = true,
                            isAvailablePluginsLoading = refreshAfterCache,
                            availablePluginsError = catalog.errors.distinct().joinToString("\n").ifBlank { null },
                        )
                    }
                    if (refreshAfterCache) {
                        availablePluginsJob = null
                        loadAvailablePlugins(forceReload = true)
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (serviceController !== controller) return@onFailure
                    mutableState.update {
                        it.copy(
                            availablePluginsLoaded = true,
                            isAvailablePluginsLoading = false,
                            availablePluginsError = error.message?.take(300)
                                ?: "Available plugins could not be refreshed",
                        )
                    }
                }
        }
    }

    private suspend fun ensureCodexMobilePluginSource(controller: CodexSessionController): Boolean {
        if (
            codexMobilePluginSourceAdded ||
            CODEX_MOBILE_PLUGIN_SOURCE_ID !in mutableState.value.enabledPluginSourceIds
        ) return false
        controller.addPluginMarketplace(CODEX_MOBILE_PLUGIN_SOURCE_URL)
        codexMobilePluginSourceAdded = true
        uiPreferences.setCodexMobilePluginSourceAdded(true)
        return true
    }

    private fun registerDiscoveredPluginSources(plugins: List<AgentPluginSummary>) {
        val discovered = plugins.map { canonicalPluginSourceId(it.reference.marketplaceName) }
            .filter(String::isNotBlank)
            .toSet()
        if (discovered.isEmpty()) return
        mutableState.update {
            val newIds = discovered - it.knownPluginSourceIds
            it.copy(
                knownPluginSourceIds = it.knownPluginSourceIds + discovered,
                enabledPluginSourceIds = it.enabledPluginSourceIds + (newIds - OPENAI_PLUGIN_SOURCE_ID),
            )
        }
        persistPluginSourceSelection()
    }

    private fun persistPluginSourceSelection() {
        val current = mutableState.value
        uiPreferences.savePluginSourceSelection(current.knownPluginSourceIds, current.enabledPluginSourceIds)
    }

    private suspend fun refreshConnectors(
        controller: CodexSessionController,
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
                isPluginDetailLoading = false,
                extensionActionError = ExtensionActionError(operationId, message),
                unavailablePluginIds = unavailable?.let { failure ->
                    it.unavailablePluginIds + failure.pluginId
                } ?: it.unavailablePluginIds,
            )
        }
        if (unavailable != null) loadAvailablePlugins(forceReload = true)
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

    private fun releaseServiceBinding() {
        serviceConnection.unbind()
        serviceEnded()
    }

    private fun serviceEnded() {
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
                installedPluginsLoaded = false,
                availablePluginsLoaded = false,
                isSkillsLoading = false,
                isAvailableSkillsLoading = false,
                isInstalledPluginsLoading = false,
                isAvailablePluginsLoading = false,
                isGitHubSkillLoading = false,
                isPluginSourceLoading = false,
                isSkillSourceLoading = false,
                isExtensionMutationLoading = false,
                extensionOperationId = null,
                isPluginDetailLoading = false,
                isConversationLoading = false,
                skillsError = null,
                availableSkillsError = null,
                installedPluginsError = null,
                availablePluginsError = null,
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
        installedPluginsJob?.cancel()
        availablePluginsJob?.cancel()
        skillSourceJob?.cancel()
        pluginSourceJob?.cancel()
        skillsJob = null
        availableSkillsJob = null
        installedPluginsJob = null
        availablePluginsJob = null
        skillSourceJob = null
        pluginSourceJob = null
    }

    private fun authenticationHandoffPending(): Boolean = uiPreferences.authenticationHandoffPending()

    private fun setAuthenticationHandoffPending(pending: Boolean) {
        uiPreferences.setAuthenticationHandoffPending(pending)
    }

    private companion object {
        const val MAX_CONVERSATION_TITLE_LENGTH = 80
        const val EXISTING_SERVICE_BIND_TIMEOUT_MILLIS = 1_000L
        const val AUTHENTICATION_RECOVERY_DELAY_MILLIS = 150L
    }
}
