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
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.PLAN_CLIENT_MESSAGE_PREFIX
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginSummary
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.SessionId
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
    internal val appContext = application.applicationContext
    internal val container = (application as CodexMobileApplication).container
    internal val uiPreferences = AppPreferencesStore(appContext)
    internal val initialExtensionSources = initialExtensionSourceSelection(
        savedKnownIds = uiPreferences.savedKnownExtensionSourceIds,
        savedEnabledIds = uiPreferences.savedEnabledExtensionSourceIds,
        savedCustomSources = uiPreferences.savedCustomExtensionSources,
        appWasUpgraded = uiPreferences.appWasUpgraded,
    )
    internal val restoredPendingPluginSetups = uiPreferences.pendingPluginSetups
    internal val mutableState = MutableStateFlow(
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
    internal var serviceController: CodexSessionController? = null
    internal var serviceStartPending = false
    internal var serviceStateJob: Job? = null
    internal var notificationsEnabled: (() -> Boolean)? = null
    internal var signOutAction: (() -> Unit)? = null
    internal var signOutPending = false
    internal var chatDataRequested = false
    internal var activeAssistantMessageId: String? = null
    internal var pendingConversationId: SessionId? = null
    internal var selectionRestoredSessionId: SessionId? = null
    internal var skillsRevision = 0
    internal var pluginsRevision = 0
    internal var connectorsRevision = 0
    internal var skillsJob: Job? = null
    internal var availableSkillsJob: Job? = null
    internal var pluginsJob: Job? = null
    internal var pluginRefreshPending = false
    internal var reconciledPluginSourceIds = emptySet<String>()
    internal var extensionSourceJob: Job? = null
    internal var extensionNoticeJob: Job? = null
    internal var integrationsLoaded = restoredPendingPluginSetups.isNotEmpty()
    internal val pendingConnectorAuthentications = ArrayDeque<AgentConnector>()
    internal var connectorAuthenticationJob: Job? = null
    internal var connectorRefreshJob: Job? = null
    internal val connectorRefreshMutex = Mutex()
    internal var serviceInstanceId: String? = null
        internal set

    internal val serviceConnection = CodexServiceConnection(
        context = appContext,
        onConnected = ::serviceConnected,
        onEnded = ::serviceEnded,
    )

    internal val scope get() = viewModelScope

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

    fun authenticate() = authenticateAction()
    fun cancelAuthentication() = cancelAuthenticationAction()
    fun browserUnavailable() = browserUnavailableAction()
    internal fun sendMessage(): SendMessageOutcome = sendMessageAction()
    fun togglePlanMode() = togglePlanModeAction()
    internal fun proceedWithPlan(): SendMessageOutcome = proceedWithPlanAction()
    fun updateDraft(value: String) = updateDraftAction(value)
    fun openHistory() = openHistoryAction()
    fun closeHistory() = closeHistoryAction()
    fun updateHistorySearch(value: String) = updateHistorySearchAction(value)
    fun startNewChat() = startNewChatAction()
    internal fun resetChat(openChat: Boolean = false) = resetChatAction(openChat)
    fun openConversation(sessionId: SessionId) = openConversationAction(sessionId)
    fun togglePinConversation(sessionId: SessionId) = togglePinConversationAction(sessionId)
    fun renameConversation(sessionId: SessionId, title: String) = renameConversationAction(sessionId, title)
    fun deleteConversation(sessionId: SessionId) = deleteConversationAction(sessionId)
    fun openSettings() = openSettingsAction()
    fun closeSettings() = closeSettingsAction()
    fun openHooks() = openHooksAction()
    fun closeHooks() = closeHooksAction()
    fun refreshHooks() = refreshHooksAction()
    fun setHookEnabled(hook: AgentHook, enabled: Boolean) = setHookEnabledAction(hook, enabled)
    fun trustHook(hook: AgentHook) = trustHookAction(hook)
    internal fun loadHooks() = loadHooksAction()
    fun openExtensions(type: ExtensionType, returnScreen: AppScreen) = openExtensionsAction(type, returnScreen)
    fun closeExtensions() = closeExtensionsAction()
    fun refreshExtensions() = refreshExtensionsAction()
    fun selectExtensionType(type: ExtensionType) = selectExtensionTypeAction(type)
    fun selectExtensionStatus(status: ExtensionStatus) = selectExtensionStatusAction(status)
    fun searchExtensions(query: String) = searchExtensionsAction(query)
    fun openExtensionSources() = openExtensionSourcesAction()
    fun closeExtensionSources() = closeExtensionSourcesAction()
    fun toggleExtensionSource(sourceId: String, enabled: Boolean) = toggleExtensionSourceAction(sourceId, enabled)
    fun addExtensionSource(url: String) = addExtensionSourceAction(url)
    fun dismissExtensionSource() = dismissExtensionSourceAction()
    fun installSkill(packageInfo: AgentSkillPackage) = installSkillAction(packageInfo)
    fun requestUninstallSkill(skill: AgentSkill) = requestUninstallSkillAction(skill)
    fun installPlugin(plugin: AgentPluginReference) = installPluginAction(plugin)
    fun connectPlugin(plugin: AgentPluginReference) = connectPluginAction(plugin)
    internal fun uninstallPlugin(plugin: AgentPluginReference, displayName: String) = uninstallPluginAction(plugin, displayName)
    fun requestUninstallPlugin(plugin: AgentPluginReference, displayName: String) = requestUninstallPluginAction(plugin, displayName)
    fun dismissExtensionRemoval() = dismissExtensionRemovalAction()
    fun confirmExtensionRemoval() = confirmExtensionRemovalAction()
    fun connectorAuthenticationReturned() = connectorAuthenticationReturnedAction()
    fun openProviderSettings(pluginId: String) = openProviderSettingsAction(pluginId)
    fun resolveElicitation(requestId: String, response: AgentElicitationResponse) = resolveElicitationAction(requestId, response)
    fun openSelector(selector: ChatSelector) = openSelectorAction(selector)
    fun dismissSelector() = dismissSelectorAction()
    fun selectModel(modelId: String) = selectModelAction(modelId)
    fun selectEffort(effort: String) = selectEffortAction(effort)
    fun selectSpeed(tier: String?) = selectSpeedAction(tier)
    fun selectApproval(preset: AgentApprovalPreset) = selectApprovalAction(preset)
    fun resolveCodexApproval(requestId: String, decision: AgentApprovalDecision) = resolveCodexApprovalAction(requestId, decision)
    fun addCapability(capability: AgentCapability) = addCapabilityAction(capability)
    fun addInvocation(invocation: AgentInvocation) = addInvocationAction(invocation)
    fun removeInvocation(key: String) = removeInvocationAction(key)
    fun removeCapability(capability: AgentCapability) = removeCapabilityAction(capability)
    fun cancelTurn() = cancelTurnAction()
    fun signOut() = performSignOut()
    fun eraseAppData() = eraseAppDataAction()
    fun workspaceRoots(): List<String> = workspaceRootsAction()
    fun workspaceDirectories(path: String?): List<String> = workspaceDirectoriesAction(path)
    fun workspaceParent(path: String): String? = workspaceParentAction(path)
    fun selectWorkspace(path: String) = selectWorkspaceAction(path)
    fun refreshStorage() = refreshStorageAction()
    internal fun serviceConnected(binder: CodexForegroundService.LocalBinder) = serviceConnectedAction(binder)
    internal suspend fun refreshChatData(controller: CodexSessionController) = refreshChatDataAction(controller)
    internal fun loadCurrentExtensions(forceReload: Boolean) = loadCurrentExtensionsAction(forceReload)
    internal fun loadSkills(forceReload: Boolean) = loadSkillsAction(forceReload)
    internal fun loadAvailableSkills(forceReload: Boolean) = loadAvailableSkillsAction(forceReload)
    internal fun loadPluginCatalog(forceReload: Boolean, allowFollowUp: Boolean = true) = loadPluginCatalogAction(forceReload, allowFollowUp)
    internal suspend fun reconcileEnabledPluginSources(controller: CodexSessionController): List<String> = reconcileEnabledPluginSourcesAction(controller)
    internal fun registerDiscoveredPluginSources(plugins: List<AgentPluginSummary>) = registerDiscoveredPluginSourcesAction(plugins)
    internal fun persistExtensionSourceSelection() = persistExtensionSourceSelectionAction()
    internal suspend fun refreshConnectors( controller: CodexSessionController, forceReload: Boolean, ): List<AgentConnector>? = refreshConnectorsAction(controller, forceReload)
    internal fun setPendingPluginSetup(pluginId: String, connectorIds: Set<String>) = setPendingPluginSetupAction(pluginId, connectorIds)
    internal fun reconcileStoredPluginSetups( connectors: List<AgentConnector>, installedPluginIds: Set<String>? = null, ) = reconcileStoredPluginSetupsAction(connectors, installedPluginIds)
    internal fun showExtensionNotice(message: String, isError: Boolean = false) = showExtensionNoticeAction(message, isError)
    internal fun cancelExtensionNotice() = cancelExtensionNoticeAction()
    internal fun extensionMutation(operationId: String, message: String, block: suspend () -> Unit) = extensionMutationAction(operationId, message, block)
    internal fun extensionFailure(error: Throwable, fallback: String = "Extension request failed") = extensionFailureAction(error, fallback)
    internal fun beginAppAuthentication(connector: AgentConnector) = beginAppAuthenticationAction(connector)
    internal fun enqueueConnectorAuthentication(connectors: List<AgentConnector>) = enqueueConnectorAuthenticationAction(connectors)
    internal fun beginNextConnectorAuthentication() = beginNextConnectorAuthenticationAction()
    internal fun beginOnUseAuthentication(state: AppUiState): Boolean = beginOnUseAuthenticationAction(state)
    internal fun refreshConversations() = refreshConversationsAction()
    internal fun persistSelection() = persistSelectionAction()
    internal fun persistPinnedConversations(ids: Set<String>) = persistPinnedConversationsAction(ids)
    internal fun applySessionState( session: CodexSessionState, notificationVisible: Boolean, ) = applySessionStateAction(session, notificationVisible)
    internal fun releaseServiceBinding() = releaseServiceBindingAction()
    internal fun serviceEnded() = serviceEndedAction()
    internal fun cancelServiceRequests() = cancelServiceRequestsAction()
    internal fun authenticationHandoffPending(): Boolean = authenticationHandoffPendingAction()
    internal fun setAuthenticationHandoffPending(pending: Boolean) = setAuthenticationHandoffPendingAction(pending)

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
}
