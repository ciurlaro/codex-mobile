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
import io.github.ciurlaro.codexmobile.app.presentation.model.CustomExtensionSource
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
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
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

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
        if (!submitted) return SendMessageOutcome.HANDLED

        val assistantId = "stream-$clientMessageId"
        activeAssistantMessageId = assistantId
        mutableState.update { it.withSubmittedTurn(request, assistantId, shellCommand) }
        return SendMessageOutcome.HANDLED
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

    fun openExtensions(type: ExtensionType, returnScreen: AppScreen) {
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
        mutableState.update {
            it.copy(
                extensionActionError = null,
                unavailablePluginIds = emptySet(),
                extensionNotice = null,
            )
        }
        if (mutableState.value.extensionType == ExtensionType.PLUGINS) reconciledPluginSourceIds = emptySet()
        loadCurrentExtensions(forceReload = true)
    }

    fun selectExtensionType(type: ExtensionType) {
        mutableState.update {
            it.copy(
                extensionType = type,
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
                    extensionNotice = ExtensionNotice(
                        when {
                            skills.isNotEmpty() && marketplaceName != null -> "Source added for skills and plugins"
                            skills.isNotEmpty() -> "Source added for skills; plugin check failed: " +
                                (pluginResult.exceptionOrNull()?.message ?: "no marketplace found").take(120)
                            marketplaceName != null -> "Source added for plugins; skill check failed: " +
                                (skillResult.exceptionOrNull()?.message ?: "no skills found").take(120)
                            else -> "Source settings were preserved"
                        },
                    ),
                )
            }
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
        val installed = mutableState.value.availablePlugins
            .firstOrNull { it.reference.id == plugin.id }
            ?.copy(installed = true, enabled = true)
        val result = serviceController?.installPlugin(plugin) ?: return@extensionMutation
        val displayName = installed?.displayName ?: plugin.name.replaceFirstChar(Char::uppercase)
        val notice = result.message ?: "$displayName installed"
        mutableState.update {
            it.copy(
                statusMessage = notice,
                extensionNotice = ExtensionNotice(notice),
                installedPlugins = installed?.let { summary ->
                    (it.installedPlugins.filterNot { candidate -> candidate.reference.id == plugin.id } + summary)
                } ?: it.installedPlugins,
                availablePlugins = it.availablePlugins.filterNot { candidate -> candidate.reference.id == plugin.id },
                unavailablePluginIds = it.unavailablePluginIds - plugin.id,
                extensionActionError = null,
            )
        }
        loadPluginCatalog(forceReload = true)
        if (result.authPolicy == AgentPluginAuthPolicy.ON_INSTALL) {
            enqueueConnectorAuthentication(result.connectorsNeedingAuthentication)
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
        mutableState.update {
            it.copy(
                statusMessage = notice,
                extensionNotice = ExtensionNotice(notice, isError = !result.completed),
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
        loadPluginCatalog(forceReload = false)
        if (mutableState.value.screen == AppScreen.EXTENSIONS) loadCurrentExtensions(forceReload = false)
    }

    private fun loadCurrentExtensions(forceReload: Boolean) {
        val current = mutableState.value
        when (current.extensionType) {
            ExtensionType.SKILLS -> when (current.extensionStatus) {
                ExtensionStatus.INSTALLED -> loadSkills(forceReload)
                ExtensionStatus.UNINSTALLED -> loadAvailableSkills(forceReload)
                ExtensionStatus.UNAVAILABLE -> Unit
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
            mutableState.update {
                it.copy(
                    installedPlugins = installedPlugins,
                    availablePlugins = availablePlugins,
                    pluginCatalogStatus = status,
                    pluginCatalogError = errors.joinToString("\n").ifBlank { null },
                )
            }

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
                    isStreaming = session.isTurnActive,
                    exitCode = session.shellExitCode,
                )
            } ?: current.messages
            current.copy(
                statusMessage = session.statusMessage,
                streamedText = session.streamedText,
                streamedReasoning = session.streamedReasoning,
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
        extensionSourceJob?.cancel()
        skillsJob = null
        availableSkillsJob = null
        pluginsJob = null
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
