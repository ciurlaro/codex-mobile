package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.app.persistence.AppPreferences
import io.github.ciurlaro.codexmobile.app.persistence.AppPreferenceState
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionController
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionState
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.SessionId
import kotlin.collections.ArrayDeque
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class AppViewModel(
    internal val platform: AppPlatform,
    internal val uiPreferences: AppPreferences,
    internal val sessionHost: AppSessionHost,
) : ViewModel() {
    internal var preferenceState = AppPreferenceState()
    internal val mutableState = MutableStateFlow(
        AppUiState(
            hasStorageAccess = platform.hasStoragePermission(),
            workspacePath = platform.configuredWorkspacePath(),
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
    internal var pluginsJob: Job? = null
    internal var pluginRefreshPending = false
    internal var extensionNoticeJob: Job? = null
    internal var integrationsLoaded = false
    internal val pendingConnectorAuthentications = ArrayDeque<AgentConnector>()
    internal var connectorAuthenticationJob: Job? = null
    internal var connectorRefreshJob: Job? = null
    internal val connectorRefreshMutex = Mutex()
    var serviceInstanceId: String? = null
        internal set

    internal val scope get() = viewModelScope

    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { applyLoadedPreferences(uiPreferences.load()) }
        sessionHost.attach(::serviceConnected, ::serviceEnded)
        viewModelScope.launch {
            sessionHost.failure.collect { failure ->
                failure?.let { message ->
                    serviceStartPending = false
                    setAuthenticationHandoffPending(false)
                    mutableState.update {
                        it.copy(statusMessage = message, isBackgroundActive = false, isAuthenticationInProgress = false)
                    }
                }
            }
        }
        val backgroundWasActive = sessionHost.wasActive()
        sessionHost.bind()
        if (backgroundWasActive) {
            viewModelScope.launch {
                delay(EXISTING_SERVICE_BIND_TIMEOUT_MILLIS)
                if (serviceController == null && sessionHost.wasActive()) {
                    sessionHost.unbind()
                    mutableState.update {
                        it.copy(statusMessage = "Previous background work ended unexpectedly; recovery was checked")
                    }
                    sessionHost.markInactive()
                }
            }
        }
    }

    fun authenticate() = authenticateAction()
    fun cancelAuthentication() = cancelAuthenticationAction()
    fun browserUnavailable() = browserUnavailableAction()
    fun sendMessage(): SendMessageOutcome = sendMessageAction()
    fun togglePlanMode() = togglePlanModeAction()
    fun proceedWithPlan(): SendMessageOutcome = proceedWithPlanAction()
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
    fun installPlugin(plugin: AgentPluginReference) = installPluginAction(plugin)
    fun connectPlugin(plugin: AgentPluginReference) = connectPluginAction(plugin)
    internal fun uninstallPlugin(plugin: AgentPluginReference, displayName: String) = uninstallPluginAction(plugin, displayName)
    fun requestUninstallPlugin(plugin: AgentPluginReference, displayName: String) = requestUninstallPluginAction(plugin, displayName)
    fun dismissExtensionRemoval() = dismissExtensionRemovalAction()
    fun confirmExtensionRemoval() = confirmExtensionRemovalAction()
    fun connectorAuthenticationReturned() = connectorAuthenticationReturnedAction()
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
    internal fun serviceConnected(handle: AppSessionHandle) = serviceConnectedAction(handle)
    internal suspend fun refreshChatData(controller: CodexSessionController) = refreshChatDataAction(controller)
    internal fun loadCurrentExtensions(forceReload: Boolean) = loadCurrentExtensionsAction(forceReload)
    internal fun loadSkills(forceReload: Boolean) = loadSkillsAction(forceReload)
    internal fun loadPluginCatalog(forceReload: Boolean, allowFollowUp: Boolean = true) = loadPluginCatalogAction(forceReload, allowFollowUp)
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
    internal fun applyLoadedPreferences(preferences: AppPreferenceState) = applyLoadedPreferencesAction(preferences)

    override fun onCleared() {
        serviceStateJob?.cancel()
        skillsJob?.cancel()
        pluginsJob?.cancel()
        connectorAuthenticationJob?.cancel()
        connectorRefreshJob?.cancel()
        extensionNoticeJob?.cancel()
        sessionHost.unbind()
        serviceController = null
        notificationsEnabled = null
        signOutAction = null
        signOutPending = false
        serviceInstanceId = null
    }
}
