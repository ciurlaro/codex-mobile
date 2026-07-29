package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.agent.AgentClient
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentConversation
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentElicitation
import io.github.ciurlaro.codexmobile.agent.AgentElicitationAction
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentHookCatalog
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.agent.AgentPluginDetail
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackageCatalog
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.SessionId
import io.github.ciurlaro.codexmobile.extension.host.AndroidSkillPackageManager
import io.github.ciurlaro.codexmobile.extension.host.AndroidPluginMarketplaceManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class CodexSessionController(
    internal val agentClient: AgentClient,
    internal val scope: CoroutineScope,
    internal val skillPackages: AndroidSkillPackageManager? = null,
    internal val pluginMarketplaces: AndroidPluginMarketplaceManager? = null,
) : AutoCloseable {
    internal val mutableState = MutableStateFlow(CodexSessionState())
    internal val turnClaimed = AtomicBoolean(false)
    internal val turnStartCompleted = AtomicBoolean(false)
    internal val cancellationStarted = AtomicBoolean(false)
    internal val cancellationDispatched = AtomicBoolean(false)
    internal val closed = AtomicBoolean(false)
    internal val lock = Any()
    internal val externalOperationMutex = Mutex()
    internal var authenticationStarted = false
    internal var eventJob: Job = scope.launch { agentClient.events.collect(::reduce) }

    val state: StateFlow<CodexSessionState> = mutableState.asStateFlow()

    fun authenticate() = authenticateAction()
    fun cancelAuthentication() = cancelAuthenticationAction()
    fun submit(request: AgentTurnRequest): Boolean = submitAction(request)
    fun submitShell(command: String, settings: AgentRuntimeSettings): Boolean = submitShellAction(command, settings)
    internal fun beginTurn(statusMessage: String): Boolean = beginTurnAction(statusMessage)
    fun resolveApproval(requestId: String, decision: AgentApprovalDecision) = resolveApprovalAction(requestId, decision)
    fun resolveElicitation(requestId: String, response: AgentElicitationResponse) = resolveElicitationAction(requestId, response)
    fun startNewChat(): Boolean = startNewChatAction()
    fun openConversation( sessionId: SessionId, settings: AgentRuntimeSettings = AgentRuntimeSettings(), ): Boolean = openConversationAction(sessionId, settings)
    suspend fun listModels(): List<AgentModel> = listModelsAction()
    suspend fun listSkills(workingDirectory: String, forceReload: Boolean = false): AgentSkillCatalog = listSkillsAction(workingDirectory, forceReload)
    suspend fun readSkill(path: String, offset: Long = 0) = readSkillAction(path, offset)
    suspend fun setSkillEnabled(path: String, enabled: Boolean) = setSkillEnabledAction(path, enabled)
    suspend fun listAvailableSkills( installedNames: Set<String>, forceRefresh: Boolean = false, ): AgentSkillPackageCatalog = listAvailableSkillsAction(installedNames, forceRefresh)
    suspend fun discoverGitHubSkills(url: String): List<AgentSkillPackage> = discoverGitHubSkillsAction(url)
    suspend fun readSkillPackage(packageInfo: AgentSkillPackage, offset: Long = 0) = readSkillPackageAction(packageInfo, offset)
    suspend fun installSkill(packageInfo: AgentSkillPackage) = installSkillAction(packageInfo)
    suspend fun uninstallSkill(skill: AgentSkill) = uninstallSkillAction(skill)
    suspend fun listInstalledPlugins( workingDirectory: String?, forceRefresh: Boolean = false, ): AgentPluginCatalog = listInstalledPluginsAction(workingDirectory, forceRefresh)
    suspend fun listAvailablePlugins( workingDirectory: String?, forceRefresh: Boolean = false, ): AgentPluginCatalog = listAvailablePluginsAction(workingDirectory, forceRefresh)
    suspend fun addPluginMarketplace(sourceUrl: String, reuseSnapshot: Boolean = false): String = addPluginMarketplaceAction(sourceUrl, reuseSnapshot)
    suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail = readPluginAction(plugin)
    suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult = installPluginAction(plugin)
    suspend fun uninstallPlugin(plugin: AgentPluginReference): AgentPluginRemovalResult = uninstallPluginAction(plugin)
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) = setPluginEnabledAction(pluginId, enabled)
    suspend fun listConnectors(forceReload: Boolean = false): List<AgentConnector> = listConnectorsAction(forceReload)
    suspend fun listMcpServers(): List<AgentMcpServer> = listMcpServersAction()
    suspend fun listHooks(workingDirectory: String): AgentHookCatalog = listHooksAction(workingDirectory)
    suspend fun setHookEnabled(key: String, enabled: Boolean) = setHookEnabledAction(key, enabled)
    suspend fun trustHook(key: String, currentHash: String) = trustHookAction(key, currentHash)
    suspend fun startMcpOauth(serverName: String): String = startMcpOauthAction(serverName)
    suspend fun listConversations(): List<AgentConversationSummary> = listConversationsAction()
    suspend fun readConversation(sessionId: SessionId): AgentConversation = readConversationAction(sessionId)
    suspend fun renameConversation(sessionId: SessionId, title: String) = renameConversationAction(sessionId, title)
    suspend fun deleteConversation(sessionId: SessionId) = deleteConversationAction(sessionId)
    fun cancelTurn() = cancelTurnAction()
    suspend fun stopAndClose(reason: String, signOut: Boolean = false): Boolean = stopAndCloseAction(reason, signOut)
    override fun close() = closeAction()
    internal fun reduce(event: AgentEvent) = reduceAction(event)
    internal fun appendStreamedText(sessionId: SessionId, text: String) = appendStreamedTextAction(sessionId, text)
    internal fun appendReasoningSummary(event: AgentEvent.ReasoningSummaryDelta) = appendReasoningSummaryAction(event)
    internal fun appendThoughtText( sessionId: SessionId, text: String, itemId: String, summaryIndex: Long?, ) = appendThoughtTextAction(sessionId, text, itemId, summaryIndex)
    internal fun appendPlan(event: AgentEvent.PlanDelta) = appendPlanAction(event)
    internal suspend fun <T> runExternalOperation(label: String, block: suspend () -> T): T = runExternalOperationAction(label, block)
    internal suspend fun <T> runPluginOperation(label: String, block: suspend () -> T): T = runPluginOperationAction(label, block)
    internal fun dispatchCancellation(sessionId: SessionId) = dispatchCancellationAction(sessionId)
    internal fun resetTurnState() = resetTurnStateAction()
    internal fun resetAuthenticationState() = resetAuthenticationStateAction()
    internal fun launchVisibleFailure( resetAuthentication: Boolean = false, resetTurn: Boolean = false, resetCancellation: Boolean = false, block: suspend () -> Unit, ) = launchVisibleFailureAction(resetAuthentication, resetTurn, resetCancellation, block)
}
