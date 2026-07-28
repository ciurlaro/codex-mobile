package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.transport.CodexRuntimeFactory
import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentHookCatalog
import io.github.ciurlaro.codexmobile.core.AgentHookActivity
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.core.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkillChunk
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.KSerializer

class CodexAgentClient(
    runtimeFactory: CodexRuntimeFactory,
    requestTimeoutMillis: Long = 20_000,
    internal val clientVersion: String = "test",
    internal val pluginCacheDirectory: File? = null,
    threadProviderStateDirectory: File? = null,
    shellTranscriptDirectory: File? = null,
    turnInputMetadataDirectory: File? = null,
    internal val builtInToolDispatcher: BuiltInToolDispatcher? = null,
    internal val providerHost: PluginProviderHost? = null,
    internal val pluginRequestTimeoutMillis: Long = 120_000,
    internal val emptyPluginCatalogRetryDelaysMillis: List<Long> = EMPTY_PLUGIN_CATALOG_RETRY_DELAYS_MILLIS,
) : AgentClient {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val eventsChannel = Channel<AgentEvent>(capacity = EVENT_BUFFER_SIZE)
    internal val authMutex = Mutex()
    internal val loginStateLock = Any()
    internal val cancelledLoginIds = mutableSetOf<String>()
    internal val pendingApprovalRequests = ConcurrentHashMap<String, PendingApproval>()
    internal val pendingBuiltInApprovals = ConcurrentHashMap<String, PendingBuiltInApproval>()
    internal val pendingElicitationRequests = ConcurrentHashMap<String, PendingElicitation>()
    internal val workItems = ConcurrentHashMap<String, Pair<SessionId, AgentWorkActivity>>()
    internal val userShellItems = ConcurrentHashMap.newKeySet<String>()
    internal val commentaryItems = ConcurrentHashMap.newKeySet<String>()
    internal val knownSkillPaths = ConcurrentHashMap.newKeySet<String>()
    internal val openedSessions = ConcurrentHashMap.newKeySet<SessionId>()
    internal val sessionRuntimeSettings = ConcurrentHashMap<SessionId, SessionRuntimeSettings>()
    internal val pendingAvailabilityNotices = ConcurrentHashMap<SessionId, PendingAvailabilityNotice>()
    internal val threadProviderStateStore = ThreadProviderStateStore(threadProviderStateDirectory)
    internal val shellTranscriptStore = ShellTranscriptStore(shellTranscriptDirectory)
    internal val turnInputMetadataStore = TurnInputMetadataStore(turnInputMetadataDirectory)
    internal val threadProviderStates = ConcurrentHashMap<SessionId, ThreadProviderState>()
    @Volatile
    internal var builtInToolDefinitions = builtInToolDispatcher?.definitions().orEmpty()
    @Volatile
    internal var builtInToolsByName = builtInToolDefinitions.associateBy(BuiltInToolDefinition::name)
    internal val builtInPluginEnabled = ConcurrentHashMap<String, Boolean>().apply {
        builtInToolDefinitions.map(BuiltInToolDefinition::pluginId).distinct().forEach { put(it, true) }
    }
    internal val builtInToolGate = Mutex()
    internal val pluginRequestMutex = Mutex()
    internal val builtInEnablementLoaded = AtomicBoolean(false)
    internal val pendingProviderCompletionRunning = AtomicBoolean(false)
    internal val turnStateLock = Any()
    internal val activeTurns = mutableMapOf<SessionId, String>()
    internal val startingTurns = mutableSetOf<SessionId>()
    internal val terminalDuringStart = mutableMapOf<SessionId, String>()
    internal val cancellingTurns = mutableSetOf<SessionId>()
    internal val cancelledTurns = mutableMapOf<SessionId, String>()
    internal val authenticated = AtomicBoolean(false)
    internal val closed = AtomicBoolean(false)
    internal val connection = AppServerConnection(
        runtimeFactory = runtimeFactory,
        initializeParams = InitializeParams(
            clientInfo = ClientInfo("codex_mobile", clientVersion, "Codex Mobile"),
            capabilities = InitializeCapabilities(
                experimentalApi = true,
                mcpServerOpenaiFormElicitation = false,
            ),
        ),
        requestTimeoutMillis = requestTimeoutMillis,
    )

    init {
        require(pluginRequestTimeoutMillis > 0) { "Plugin request timeout must be positive" }
        require(emptyPluginCatalogRetryDelaysMillis.all { it >= 0 }) { "Plugin retry delays must not be negative" }
    }

    init {
        scope.launch {
            try {
                connection.events.collect(::handleConnectionEvent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed.get()) handleConnectionFailure("event_stream", error.visibleMessage())
            }
        }
    }

    @Volatile
    internal var loginId: String? = null

    internal var loginStarting = false
    internal var loginCompletedDuringStart: LoginCompletion? = null

    override val events: Flow<AgentEvent> = eventsChannel.receiveAsFlow()

    override suspend fun authenticate() = authenticateAction()
    override suspend fun cancelAuthentication() = cancelAuthenticationAction()
    override suspend fun signOut() = signOutAction()
    override suspend fun listModels(): List<AgentModel> = listModelsAction()
    override suspend fun listSkills( workingDirectory: String, forceReload: Boolean, ): AgentSkillCatalog = listSkillsAction(workingDirectory, forceReload)
    override suspend fun readSkill(path: String, offset: Long): AgentSkillChunk = readSkillAction(path, offset)
    override suspend fun setSkillEnabled(path: String, enabled: Boolean) = setSkillEnabledAction(path, enabled)
    override suspend fun listInstalledPlugins( workingDirectory: String?, forceRefresh: Boolean, ): AgentPluginCatalog = listInstalledPluginsAction(workingDirectory, forceRefresh)
    override suspend fun listAvailablePlugins( workingDirectory: String?, forceRefresh: Boolean, ): AgentPluginCatalog = listAvailablePluginsAction(workingDirectory, forceRefresh)
    internal suspend fun requestAvailablePlugins(workingDirectory: String?, cache: File?): AgentPluginCatalog = requestAvailablePluginsAction(workingDirectory, cache)
    internal suspend fun <P, R> listPlugins( workingDirectory: String?, method: AppServerMethod<P, R>, params: P, timeoutMillis: Long? = null, marketplaces: (R) -> List<PluginMarketplaceEntry>, loadErrors: (R) -> List<MarketplaceLoadErrorInfo>?, onResponse: (R) -> Unit = {}, ): AgentPluginCatalog = listPluginsAction(workingDirectory, method, params, timeoutMillis, marketplaces, loadErrors, onResponse)
    internal suspend fun <P, R> pluginRequest( method: AppServerMethod<P, R>, params: P, timeoutMillis: Long = pluginRequestTimeoutMillis, retryOnTimeout: Boolean = false, ): R = pluginRequestAction(method, params, timeoutMillis, retryOnTimeout)
    internal fun pluginCacheFile(workingDirectory: String?, kind: String): File? = pluginCacheFileAction(workingDirectory, kind)
    internal fun <T> readPluginCache( file: File?, serializer: KSerializer<T>, marketplaces: (T) -> List<PluginMarketplaceEntry>, loadErrors: (T) -> List<MarketplaceLoadErrorInfo>?, ): AgentPluginCatalog? = readPluginCacheAction(file, serializer, marketplaces, loadErrors)
    internal fun <T> writePluginCache(file: File?, serializer: KSerializer<T>, response: T) = writePluginCacheAction(file, serializer, response)
    internal fun validateWorkingDirectory(workingDirectory: String?) = validateWorkingDirectoryAction(workingDirectory)
    internal fun clearPluginCache() = clearPluginCacheAction()
    override suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail = readPluginAction(plugin)
    override suspend fun addPluginMarketplace(source: String) = addPluginMarketplaceAction(source)
    override suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult = installPluginAction(plugin)
    override suspend fun uninstallPlugin(plugin: AgentPluginReference): AgentPluginRemovalResult = uninstallPluginAction(plugin)
    override suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) = setPluginEnabledAction(pluginId, enabled)
    override suspend fun listConnectors( sessionId: SessionId?, forceReload: Boolean, ): List<AgentConnector> = listConnectorsAction(sessionId, forceReload)
    override suspend fun listMcpServers(): List<AgentMcpServer> = listMcpServersAction()
    override suspend fun listHooks(workingDirectory: String): AgentHookCatalog = listHooksAction(workingDirectory)
    override suspend fun setHookEnabled(key: String, enabled: Boolean) = setHookEnabledAction(key, enabled)
    override suspend fun trustHook(key: String, currentHash: String) = trustHookAction(key, currentHash)
    internal suspend fun writeHookState(key: String, state: JsonObjectBuilder.() -> Unit) = writeHookStateAction(key, state)
    override suspend fun startMcpOauth(serverName: String, sessionId: SessionId?): String = startMcpOauthAction(serverName, sessionId)
    override suspend fun listSessions(): List<AgentConversationSummary> = listSessionsAction()
    override suspend fun readSession(sessionId: SessionId): AgentConversation = readSessionAction(sessionId)
    override suspend fun renameSession(sessionId: SessionId, name: String) = renameSessionAction(sessionId, name)
    override suspend fun deleteSession(sessionId: SessionId) = deleteSessionAction(sessionId)
    override suspend fun openSession(previous: SessionId?, settings: AgentRuntimeSettings): SessionId = openSessionAction(previous, settings)
    override suspend fun sendTurn(sessionId: SessionId, request: AgentTurnRequest) = sendTurnAction(sessionId, request)
    override suspend fun runShellCommand(sessionId: SessionId, command: String) = runShellCommandAction(sessionId, command)
    override suspend fun cancelTurn(sessionId: SessionId) = cancelTurnAction(sessionId)
    override suspend fun resolveApproval(requestId: String, decision: AgentApprovalDecision) = resolveApprovalAction(requestId, decision)
    override suspend fun resolveElicitation( requestId: String, response: AgentElicitationResponse, ) = resolveElicitationAction(requestId, response)
    override fun close() = closeAction()
    internal suspend fun notifyOpenSessionsOfPluginAvailability() = notifyOpenSessionsOfPluginAvailabilityAction()
    internal suspend fun notifySessionOfPluginAvailability(sessionId: SessionId) = notifySessionOfPluginAvailabilityAction(sessionId)
    internal fun pluginAvailabilityNotice(availability: Map<String, Boolean>): String = pluginAvailabilityNoticeAction(availability)
    internal suspend fun sendPluginAvailabilityNotice(sessionId: SessionId, pending: PendingAvailabilityNotice) = sendPluginAvailabilityNoticeAction(sessionId, pending)
    internal suspend fun flushPluginAvailabilityNotice(sessionId: SessionId) = flushPluginAvailabilityNoticeAction(sessionId)
    internal suspend fun refreshBuiltInPluginEnablement(workingDirectory: String) = refreshBuiltInPluginEnablementAction(workingDirectory)
    internal fun applyBuiltInPluginEnablement(catalog: AgentPluginCatalog) = applyBuiltInPluginEnablementAction(catalog)
    internal suspend fun handleConnectionEvent(event: AppServerEvent) = handleConnectionEventAction(event)
    internal suspend fun handleServerRequest(request: ServerRequest, method: String) = handleServerRequestAction(request, method)
    internal fun handleBuiltInToolCall(id: JsonElement, params: DynamicToolCallParams) = handleBuiltInToolCallAction(id, params)
    internal suspend fun continueBuiltInToolCall(pending: PendingBuiltInApproval) = continueBuiltInToolCallAction(pending)
    internal suspend fun executeBuiltInTool(pending: PendingBuiltInApproval) = executeBuiltInToolAction(pending)
    internal fun validateBuiltInCall(pending: PendingBuiltInApproval) = validateBuiltInCallAction(pending)
    internal fun cancelPendingBuiltInTools(sessionId: SessionId, turnId: String?, message: String) = cancelPendingBuiltInToolsAction(sessionId, turnId, message)
    internal suspend fun respondBuiltInResult(id: JsonElement, result: BuiltInToolResult) = respondBuiltInResultAction(id, result)
    internal suspend fun handleElicitationRequest( id: JsonElement, params: McpServerElicitationRequestParams, ) = handleElicitationRequestAction(id, params)
    internal suspend fun handleUserInputRequest(id: JsonElement, params: ToolRequestUserInputParams) = handleUserInputRequestAction(id, params)
    internal suspend fun handleApprovalRequest( id: JsonElement, threadId: String, reason: String?, detailLines: List<String>, type: ApprovalType, ) = handleApprovalRequestAction(id, threadId, reason, detailLines, type)
    internal suspend fun rejectServerRequest(id: JsonElement, method: String) = rejectServerRequestAction(id, method)
    internal suspend fun respondServerError(id: JsonElement, code: Int, message: String) = respondServerErrorAction(id, code, message)
    internal suspend fun handleNotification(notification: ServerNotification) = handleNotificationAction(notification)
    internal suspend fun emitAuthenticated() = emitAuthenticatedAction()
    internal suspend fun applyLoginCompletion(completion: LoginCompletion) = applyLoginCompletionAction(completion)
    internal suspend fun finishTurn(sessionId: SessionId, turnId: String?) = finishTurnAction(sessionId, turnId)
    internal suspend fun updateItemActivity( threadId: String, turnId: String, item: ThreadItem, started: Boolean, ) = updateItemActivityAction(threadId, turnId, item, started)
    internal suspend fun completeUserShellItem(threadId: String, turnId: String, item: ThreadItem) = completeUserShellItemAction(threadId, turnId, item)
    internal suspend fun handleConnectionFailure(code: String, message: String) = handleConnectionFailureAction(code, message)
    internal fun shellTranscriptMessages(transcript: ShellTranscript): List<AgentMessage> = shellTranscriptMessagesAction(transcript)
    internal suspend fun <P, R, T, U> requestAllPages( method: AppServerMethod<P, R>, params: (String?) -> P, data: (R) -> List<T>, nextCursor: (R) -> String?, transform: (T) -> U, ): List<U> = requestAllPagesAction(method, params, data, nextCursor, transform)
    internal fun pluginReadParams(plugin: AgentPluginReference) = pluginReadParamsAction(plugin)
    internal fun pluginInstallParams(plugin: AgentPluginReference) = pluginInstallParamsAction(plugin)
    internal fun pluginUninstallParams(plugin: AgentPluginReference) = pluginUninstallParamsAction(plugin)
    internal fun pluginEnablementParams(pluginId: String, enabled: Boolean) = pluginEnablementParamsAction(pluginId, enabled)
    internal fun approvalsReviewer(preset: AgentApprovalPreset) = approvalsReviewerAction(preset)
    internal suspend fun disableManagedProviderMcp(pluginId: String) = disableManagedProviderMcpAction(pluginId)
    internal fun refreshBuiltInTools() = refreshBuiltInToolsAction()
    internal suspend fun completePendingProviderInstalls() = completePendingProviderInstallsAction()
    internal suspend fun completePreparedProviderRemovals(catalog: AgentPluginCatalog) = completePreparedProviderRemovalsAction(catalog)
    internal fun reconcileProvidersInBackground(catalog: AgentPluginCatalog? = null) = reconcileProvidersInBackgroundAction(catalog)
    internal fun parseGitHubMarketplaceSource(value: String): MarketplaceSource = parseGitHubMarketplaceSourceAction(value)
    internal fun elicitationResponse(response: AgentElicitationResponse): McpServerElicitationRequestResponse = elicitationResponseAction(response)

    internal fun AgentPluginCatalog.asStale(message: String): AgentPluginCatalog = copy(
        freshness = AgentCatalogFreshness.STALE_CACHE,
        errors = (errors + message).distinct(),
    )

    internal fun AgentPluginCatalog.withCachedFallback(
        cached: AgentPluginCatalog,
        message: String,
    ): AgentPluginCatalog = copy(
        plugins = (cached.plugins + plugins).associateBy { it.reference.id }.values.toList(),
        freshness = AgentCatalogFreshness.STALE_CACHE,
        errors = (cached.errors + errors + message).distinct(),
    )

    internal fun HookRunSummary.toAgentHookActivity() = AgentHookActivity(
        id = id,
        eventName = eventName.name,
        handlerType = handlerType.name,
        status = enumValueOf(status.name),
        statusMessage = statusMessage,
        details = entries.map(HookOutputEntry::text),
    )

    internal fun String.boundedShellTranscript(): String =
        if (length <= MAX_SHELL_TRANSCRIPT_CHARS) this
        else take(MAX_SHELL_TRANSCRIPT_CHARS) + TRUNCATION_MARKER

    internal fun AgentPluginReference.appServerPluginName(): String = if (marketplacePath == null) {
        requireNotNull(remotePluginId) { "Remote plugin $id is missing its catalog identifier; refresh the catalog" }
    } else {
        name
    }

    internal fun AppServerRpcException.forPlugin(plugin: AgentPluginReference): Throwable =
        if (detail.contains("Plugin not found", ignoreCase = true) ||
            detail.contains("status 404", ignoreCase = true) && detail.contains("/plugins/")) {
            AgentPluginUnavailableException(
                plugin.id,
                plugin.name.replace('-', ' ').replaceFirstChar(Char::uppercase),
            )
        } else {
            this
        }
}
