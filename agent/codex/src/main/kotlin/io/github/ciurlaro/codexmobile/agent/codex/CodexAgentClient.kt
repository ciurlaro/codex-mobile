package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.transport.CodexRuntimeFactory
import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentFormValue
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
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
import io.github.ciurlaro.codexmobile.core.AgentServiceTier
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkillChunk
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalState
import java.io.File
import java.net.URI
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class CodexAgentClient(
    runtimeFactory: CodexRuntimeFactory,
    requestTimeoutMillis: Long = 20_000,
    private val clientVersion: String = "test",
    private val pluginCacheDirectory: File? = null,
    threadProviderStateDirectory: File? = null,
    private val builtInToolDispatcher: BuiltInToolDispatcher? = null,
    private val providerHost: PluginProviderHost? = null,
) : AgentClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventsChannel = Channel<AgentEvent>(capacity = EVENT_BUFFER_SIZE)
    private val authMutex = Mutex()
    private val loginStateLock = Any()
    private val cancelledLoginIds = mutableSetOf<String>()
    private val pendingApprovalRequests = ConcurrentHashMap<String, PendingApproval>()
    private val pendingBuiltInApprovals = ConcurrentHashMap<String, PendingBuiltInApproval>()
    private val pendingElicitationRequests = ConcurrentHashMap<String, JsonElement>()
    private val workItems = ConcurrentHashMap<String, Pair<SessionId, AgentWorkActivity>>()
    private val userShellItems = ConcurrentHashMap.newKeySet<String>()
    private val knownSkillPaths = ConcurrentHashMap.newKeySet<String>()
    private val openedSessions = ConcurrentHashMap.newKeySet<SessionId>()
    private val sessionRuntimeSettings = ConcurrentHashMap<SessionId, SessionRuntimeSettings>()
    private val pendingAvailabilityNotices = ConcurrentHashMap<SessionId, PendingAvailabilityNotice>()
    private val threadProviderStateStore = ThreadProviderStateStore(threadProviderStateDirectory)
    private val threadProviderStates = ConcurrentHashMap<SessionId, ThreadProviderState>()
    @Volatile
    private var builtInToolDefinitions = builtInToolDispatcher?.definitions().orEmpty()
    @Volatile
    private var builtInToolsByName = builtInToolDefinitions.associateBy(BuiltInToolDefinition::name)
    private val builtInPluginEnabled = ConcurrentHashMap<String, Boolean>().apply {
        builtInToolDefinitions.map(BuiltInToolDefinition::pluginId).distinct().forEach { put(it, true) }
    }
    private val builtInToolGate = Mutex()
    private val builtInEnablementLoaded = AtomicBoolean(false)
    private val pendingProviderCompletionRunning = AtomicBoolean(false)
    private val turnStateLock = Any()
    private val activeTurns = mutableMapOf<SessionId, String>()
    private val startingTurns = mutableSetOf<SessionId>()
    private val terminalDuringStart = mutableMapOf<SessionId, String>()
    private val cancellingTurns = mutableSetOf<SessionId>()
    private val cancelledTurns = mutableMapOf<SessionId, String>()
    private val authenticated = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val connection = AppServerConnection(
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
    private var loginId: String? = null

    private var loginStarting = false
    private var loginCompletedDuringStart: LoginCompletion? = null

    override val events: Flow<AgentEvent> = eventsChannel.receiveAsFlow()

    override suspend fun authenticate() = authMutex.withLock {
        connection.ensureStarted()
        if (authenticated.get()) {
            emitAuthenticated()
            return@withLock
        }
        if (loginId != null) return@withLock

        val account = connection.request(
            AppServerClientMethods.AccountRead,
            GetAccountParams(refreshToken = false),
        ).account
        if (account is AccountChatgptAccount) {
            emitAuthenticated()
            return@withLock
        }

        synchronized(loginStateLock) {
            loginStarting = true
            loginCompletedDuringStart = null
        }
        try {
            val result = connection.request(
                AppServerClientMethods.AccountLoginStart,
                LoginAccountParamsChatgpt(
                    appBrand = LoginAppBrand.CODEX,
                    useHostedLoginSuccessPage = true,
                ),
            ) as? LoginAccountResponseChatgpt
                ?: error("App-server returned an unexpected login method")
            val startedLoginId = result.loginId
            val earlyCompletion = synchronized(loginStateLock) {
                loginStarting = false
                loginCompletedDuringStart
                    ?.takeIf { it.loginId == startedLoginId }
                    .also { loginCompletedDuringStart = null }
                    .also {
                        loginId = if (it == null && !authenticated.get()) startedLoginId else null
                    }
            }
            when {
                earlyCompletion != null -> applyLoginCompletion(earlyCompletion)
                authenticated.get() -> Unit
                else -> eventsChannel.send(
                    AgentEvent.AuthenticationRequired(
                        signInUrl = result.authUrl,
                    ),
                )
            }
        } catch (error: Exception) {
            synchronized(loginStateLock) {
                loginStarting = false
                loginCompletedDuringStart = null
            }
            throw error
        }
    }

    override suspend fun cancelAuthentication() = authMutex.withLock {
        connection.ensureStarted()
        val activeLoginId = synchronized(loginStateLock) {
            loginId?.also(cancelledLoginIds::add)
        } ?: return@withLock
        try {
            val status = connection.request(
                AppServerClientMethods.AccountLoginCancel,
                CancelLoginAccountParams(activeLoginId),
            ).status
            check(status == CancelLoginAccountStatus.CANCELED || status == CancelLoginAccountStatus.NOT_FOUND) {
                "Unexpected login cancellation status: $status"
            }
            synchronized(loginStateLock) {
                if (loginId == activeLoginId) loginId = null
                if (status == CancelLoginAccountStatus.NOT_FOUND) cancelledLoginIds -= activeLoginId
            }
        } catch (error: Exception) {
            synchronized(loginStateLock) { cancelledLoginIds -= activeLoginId }
            throw error
        }
    }

    override suspend fun signOut() = authMutex.withLock {
        connection.ensureStarted()
        connection.request(AppServerClientMethods.AccountLogout, Unit)
        authenticated.set(false)
        synchronized(loginStateLock) {
            loginId = null
            loginStarting = false
            loginCompletedDuringStart = null
            cancelledLoginIds.clear()
        }
        synchronized(turnStateLock) {
            activeTurns.clear()
            startingTurns.clear()
            terminalDuringStart.clear()
            cancellingTurns.clear()
        }
        userShellItems.clear()
        knownSkillPaths.clear()
        openedSessions.clear()
        sessionRuntimeSettings.clear()
    }

    override suspend fun listModels(): List<AgentModel> =
        requestAllPages(
            AppServerClientMethods.ModelList,
            params = { ModelListParams(cursor = it) },
            data = ModelListResponse::data,
            nextCursor = ModelListResponse::nextCursor,
        ) { item ->
        val serviceTiers = item.serviceTiers.orEmpty().map { tier ->
                AgentServiceTier(tier.id, tier.name, tier.description)
            }.distinctBy(AgentServiceTier::id)
        AgentModel(
            id = item.model,
            displayName = item.displayName,
            description = item.description,
            supportedEfforts = item.supportedReasoningEfforts.map { it.reasoningEffort },
            defaultEffort = item.defaultReasoningEffort,
            isDefault = item.isDefault,
            serviceTiers = serviceTiers,
            defaultServiceTier = item.defaultServiceTier,
        )
    }

    override suspend fun listSkills(
        workingDirectory: String,
        forceReload: Boolean,
    ): AgentSkillCatalog {
        require(workingDirectory.startsWith('/')) { "Working directory must be absolute" }
        val result = connection.request(
            AppServerClientMethods.SkillsList,
            SkillsListParams(cwds = listOf(workingDirectory), forceReload = forceReload),
        )
        val entries = result.data
        return AgentSkillCatalog(
            skills = entries.flatMap { it.skills }.map(::parseSkill)
                .distinctBy { it.path },
            errors = entries.flatMap { it.errors }.map { "${it.path}: ${it.message}" },
        ).also { catalog ->
            knownSkillPaths.clear()
            catalog.skills.mapTo(knownSkillPaths, io.github.ciurlaro.codexmobile.core.AgentSkill::path)
        }
    }

    override suspend fun readSkill(path: String, offset: Long): AgentSkillChunk = withContext(Dispatchers.IO) {
        require(path in knownSkillPaths) { "Skill was not returned by skills/list" }
        require(offset >= 0) { "Offset must not be negative" }
        val file = File(path)
        require(file.isFile && file.canRead()) { "Skill source is not readable" }
        RandomAccessFile(file, "r").use { source ->
            val total = source.length()
            require(offset <= total) { "Offset exceeds skill source size" }
            source.seek(offset)
            val bytes = ByteArray(SKILL_CHUNK_BYTES)
            val count = source.read(bytes).coerceAtLeast(0)
            val complete = if (offset + count < total) completeUtf8Length(bytes, count) else count
            val next = (offset + complete).takeIf { it < total }
            AgentSkillChunk(
                content = String(bytes, 0, complete, StandardCharsets.UTF_8),
                nextOffset = next,
                totalBytes = total,
            )
        }
    }

    override suspend fun setSkillEnabled(path: String, enabled: Boolean) {
        require(path.startsWith('/')) { "Skill path must be absolute" }
        connection.request(
            AppServerClientMethods.SkillsConfigWrite,
            SkillsConfigWriteParams(path = path, enabled = enabled),
        )
    }

    override suspend fun listInstalledPlugins(workingDirectory: String): AgentPluginCatalog {
        // Marketplace refresh failures belong to Discover; the installed inventory is still usable.
        val catalog = listPlugins(
            workingDirectory,
            AppServerClientMethods.PluginInstalled,
            PluginInstalledParams(cwds = listOf(workingDirectory)),
            marketplaces = PluginInstalledResponse::marketplaces,
            loadErrors = PluginInstalledResponse::marketplaceLoadErrors,
        ).copy(errors = emptyList())
        reconcileProvidersInBackground(catalog)
        return catalog
    }

    override suspend fun listAvailablePlugins(
        workingDirectory: String,
        forceRefresh: Boolean,
    ): AgentPluginCatalog {
        require(workingDirectory.startsWith('/')) { "Working directory must be absolute" }
        val cache = pluginCacheFile(workingDirectory)
        if (!forceRefresh) readPluginCache(cache)?.let { return it }
        return runCatching {
            val catalog = listPlugins(
                workingDirectory,
                AppServerClientMethods.PluginList,
                PluginListParams(cwds = listOf(workingDirectory)),
                PLUGIN_CATALOG_TIMEOUT_MILLIS,
                marketplaces = PluginListResponse::marketplaces,
                loadErrors = PluginListResponse::marketplaceLoadErrors,
            ) { writePluginCache(cache, it) }
            catalog
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            readPluginCache(cache, stale = true)?.copy(
                errors = listOfNotNull(error.message ?: "Available plugins could not be refreshed"),
            ) ?: throw error
        }
    }

    private suspend fun <P, R> listPlugins(
        workingDirectory: String,
        method: AppServerMethod<P, R>,
        params: P,
        timeoutMillis: Long? = null,
        marketplaces: (R) -> List<PluginMarketplaceEntry>,
        loadErrors: (R) -> List<MarketplaceLoadErrorInfo>?,
        onResponse: (R) -> Unit = {},
    ): AgentPluginCatalog {
        require(workingDirectory.startsWith('/')) { "Working directory must be absolute" }
        val result = if (timeoutMillis == null) {
            connection.request(method, params)
        } else {
            connection.request(method, params, timeoutMillis)
        }
        val errors = loadErrors(result).orEmpty().map { it.message }.distinct()
        val catalog = AgentPluginCatalog(parsePluginMarketplaces(marketplaces(result)), errors)
        if (builtInToolDispatcher != null) {
            builtInToolGate.withLock {
                applyBuiltInPluginEnablement(catalog)
                builtInEnablementLoaded.set(true)
            }
        }
        runCatching { onResponse(result) }
        return catalog
    }

    private fun pluginCacheFile(workingDirectory: String): File? {
        val directory = pluginCacheDirectory ?: return null
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$clientVersion\u0000$workingDirectory".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$key.json")
    }

    private fun readPluginCache(file: File?, stale: Boolean = false): AgentPluginCatalog? {
        if (file?.isFile != true) return null
        return runCatching {
            val result = PROTOCOL_JSON.decodeFromString(PluginListResponse.serializer(), file.readText())
            val freshness = if (!stale && System.currentTimeMillis() - file.lastModified() <= CATALOG_CACHE_TTL_MILLIS) {
                AgentCatalogFreshness.FRESH_CACHE
            } else {
                AgentCatalogFreshness.STALE_CACHE
            }
            AgentPluginCatalog(
                plugins = parsePluginMarketplaces(result.marketplaces),
                errors = result.marketplaceLoadErrors.orEmpty().map { it.message }.distinct(),
                freshness = freshness,
            )
        }.getOrNull()
    }

    private fun writePluginCache(file: File?, response: PluginListResponse) {
        if (file == null) return
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Unable to prepare plugin catalog cache"
        }
        val next = File(file.parentFile, ".${file.name}.next")
        next.writeText(PROTOCOL_JSON.encodeToString(PluginListResponse.serializer(), response))
        Files.move(
            next.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail {
        return try {
            parsePluginDetail(
                connection.request(AppServerClientMethods.PluginRead, pluginReadParams(plugin)).plugin,
            ).let { detail ->
                detail.copy(providerManaged = providerHost?.manages(plugin.id) == true)
            }
        } catch (error: AppServerRpcException) {
            throw error.forPlugin(plugin)
        }
    }

    override suspend fun addPluginMarketplace(source: String) {
        val marketplace = if (source.startsWith('/')) {
            require(source.length <= 4_096 && '\u0000' !in source) { "Invalid local marketplace path" }
            MarketplaceSource(source)
        } else {
            parseGitHubMarketplaceSource(source)
        }
        connection.request(
            AppServerClientMethods.MarketplaceAdd,
            MarketplaceAddParams(marketplace.repository, marketplace.refName, marketplace.sparsePaths),
        )
        eventsChannel.send(AgentEvent.PluginsChanged)
    }

    override suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult {
        val host = providerHost
        val detail = host?.let { readPlugin(plugin) }
        val disposition = host?.install(plugin, detail?.mcpServers.orEmpty().toSet())
            ?: ProviderInstallDisposition.NOT_REQUIRED
        if (disposition == ProviderInstallDisposition.RESTART_REQUIRED) {
            return AgentPluginInstallResult(
                authPolicy = AgentPluginAuthPolicy.ON_USE,
                connectorsNeedingAuthentication = emptyList(),
                restartRequired = true,
                message = "Restart Codex Mobile to verify and finish installing this provider.",
            )
        }
        if (disposition == ProviderInstallDisposition.READY) {
            refreshBuiltInTools()
            disableManagedProviderMcp(plugin.id)
        }
        if (disposition == ProviderInstallDisposition.READY && detail?.summary?.installed == true) {
            checkNotNull(host).installCompleted(plugin.id)
            eventsChannel.send(AgentEvent.PluginsChanged)
            return AgentPluginInstallResult(detail.summary.authPolicy, emptyList())
        }
        val result = try {
            connection.request(AppServerClientMethods.PluginInstall, pluginInstallParams(plugin))
        } catch (error: AppServerRpcException) {
            throw error.forPlugin(plugin)
        }
        if (disposition == ProviderInstallDisposition.READY) host?.installCompleted(plugin.id)
        eventsChannel.send(AgentEvent.PluginsChanged)
        return AgentPluginInstallResult(
            authPolicy = enumValueOf(result.authPolicy.name),
            connectorsNeedingAuthentication = result.appsNeedingAuth.map(::parseConnector),
        )
    }

    override suspend fun uninstallPlugin(plugin: AgentPluginReference): AgentPluginRemovalResult {
        require(plugin.id.isNotBlank()) { "Plugin ID must not be blank" }
        val host = providerHost?.takeIf { it.manages(plugin.id) }
        var removalWarning: String? = null
        if (host != null) {
            setPluginEnabled(plugin.id, false)
            val preparation = host.prepareRemoval(plugin.id)
            if (preparation.state == ProviderRemovalState.RETRY_REQUIRED) {
                return AgentPluginRemovalResult(
                    completed = false,
                    message = preparation.message ?: "Provider cleanup needs retry before uninstall can continue.",
                )
            }
            removalWarning = preparation.message
        }
        connection.request(
            AppServerClientMethods.PluginUninstall,
            PluginUninstallParams(plugin.id),
        )
        if (host != null) {
            host.remove(plugin.id)
            eventsChannel.send(AgentEvent.PluginsChanged)
            return AgentPluginRemovalResult(
                completed = false,
                restartRequired = true,
                message = listOfNotNull(
                    removalWarning,
                    "Restart Codex Mobile to verify that the provider code was removed.",
                ).joinToString(" "),
            )
        }
        eventsChannel.send(AgentEvent.PluginsChanged)
        return AgentPluginRemovalResult(completed = true)
    }

    override suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        require(pluginId.isNotBlank() && '.' !in pluginId) { "Invalid plugin ID" }
        if (builtInPluginEnabled.containsKey(pluginId)) {
            builtInToolGate.withLock {
                connection.request(
                    AppServerClientMethods.ConfigValueWrite,
                    pluginEnablementParams(pluginId, enabled),
                )
                builtInPluginEnabled[pluginId] = enabled
            }
            notifyOpenSessionsOfPluginAvailability()
        } else {
            connection.request(AppServerClientMethods.ConfigValueWrite, pluginEnablementParams(pluginId, enabled))
        }
    }

    override suspend fun listConnectors(
        sessionId: SessionId?,
        forceReload: Boolean,
    ): List<AgentConnector> = requestAllPages(
        AppServerClientMethods.AppList,
        params = { cursor -> AppsListParams(cursor, forceReload, threadId = sessionId?.value) },
        data = AppsListResponse::data,
        nextCursor = AppsListResponse::nextCursor,
        transform = ::parseConnector,
    )

    override suspend fun listMcpServers(): List<AgentMcpServer> =
        requestAllPages(
            AppServerClientMethods.McpServerStatusList,
            params = { ListMcpServerStatusParams(cursor = it) },
            data = ListMcpServerStatusResponse::data,
            nextCursor = ListMcpServerStatusResponse::nextCursor,
            transform = ::parseMcpServer,
        )
            .filterNot { it.name == INTERNAL_APPS_MCP_SERVER }

    override suspend fun startMcpOauth(serverName: String, sessionId: SessionId?): String {
        require(serverName.isNotBlank()) { "MCP server name must not be blank" }
        return connection.request(
            AppServerClientMethods.McpServerOauthLogin,
            McpServerOauthLoginParams(name = serverName, threadId = sessionId?.value),
        ).authorizationUrl.also(::requireSafeAuthUrl)
    }

    override suspend fun listSessions(): List<AgentConversationSummary> = requestAllPages(
        AppServerClientMethods.ThreadList,
        params = { cursor ->
            ThreadListParams(
                cursor = cursor,
                sortDirection = SortDirection.DESC,
                sortKey = ThreadSortKey.UPDATED_AT,
            )
        },
        data = ThreadListResponse::data,
        nextCursor = ThreadListResponse::nextCursor,
        transform = ::conversationSummary,
    )

    override suspend fun readSession(sessionId: SessionId): AgentConversation {
        val thread = connection.request(
            AppServerClientMethods.ThreadRead,
            ThreadReadParams(sessionId.value, includeTurns = true),
        ).thread
        check(thread.id == sessionId.value) { "App-server returned another thread" }
        val messages = thread.turns.flatMap { turn ->
            turn.items.mapNotNull { item ->
                conversationMessage(PROTOCOL_JSON.encodeToJsonElement(ThreadItem.serializer(), item))
            }
        }
        return AgentConversation(conversationSummary(thread), messages)
    }

    override suspend fun renameSession(sessionId: SessionId, name: String) {
        val snapshot = name.trim()
        require(snapshot.isNotEmpty()) { "Conversation name must not be blank" }
        connection.request(
            AppServerClientMethods.ThreadNameSet,
            ThreadSetNameParams(name = snapshot, threadId = sessionId.value),
        )
    }

    override suspend fun deleteSession(sessionId: SessionId) {
        connection.request(
            AppServerClientMethods.ThreadDelete,
            ThreadDeleteParams(sessionId.value),
        )
        openedSessions -= sessionId
        sessionRuntimeSettings -= sessionId
        pendingAvailabilityNotices -= sessionId
        threadProviderStates -= sessionId
        threadProviderStateStore.delete(sessionId.value)
        synchronized(turnStateLock) {
            activeTurns -= sessionId
            startingTurns -= sessionId
            terminalDuringStart -= sessionId
            cancellingTurns -= sessionId
            cancelledTurns -= sessionId
        }
    }

    override suspend fun openSession(previous: SessionId?, settings: AgentRuntimeSettings): SessionId {
        completePendingProviderInstalls()
        if (builtInToolDispatcher != null) {
            connection.ensureStarted()
            refreshBuiltInPluginEnablement(settings.workingDirectory ?: "/")
        }
        val developerInstructions =
            "Answer conversationally using Markdown. The shell starts in the user's selected Android " +
                "workspace and may use ordinary shell commands to inspect and modify files. Use enabled " +
                "plugin tools through their advertised typed contracts. Use the " +
                "built-in web search tool only when the user input contains the structured " +
                "'${AgentCapability.WEB_SEARCH.promptLabel}' prompt tag."
        val config = buildJsonObject {
            put("web_search", "live")
            putJsonObject("tools") {
                putJsonObject("experimental_request_user_input") { put("enabled", false) }
            }
            putJsonObject("features") {
                put("shell_tool", true)
                put("code_mode", false)
                put("multi_agent", false)
                put("apps", true)
                put("enable_mcp_apps", true)
                put("plugins", true)
                put("image_generation", false)
                put("goals", false)
                put("hooks", false)
                put("skill_mcp_dependency_install", false)
                put("workspace_dependencies", false)
                put("standalone_web_search", false)
            }
            putJsonObject("shell_environment_policy") {
                put("inherit", "all")
                put(
                    "exclude",
                    buildJsonArray {
                        listOf(
                            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
                            "http_proxy", "https_proxy", "all_proxy", "no_proxy",
                        ).forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        }
        val opened = if (previous == null) {
            val result = connection.request(
                AppServerClientMethods.ThreadStart,
                ThreadStartParams(
                    approvalPolicy = JsonPrimitive(settings.approvalPreset.approvalPolicy),
                    approvalsReviewer = approvalsReviewer(settings.approvalPreset),
                    config = config,
                    cwd = settings.workingDirectory,
                    developerInstructions = developerInstructions,
                    ephemeral = false,
                    sandbox = SandboxMode.DANGER_FULL_ACCESS,
                    serviceTier = settings.serviceTier,
                    dynamicTools = builtInToolDispatcher?.let {
                        builtInDynamicTools(
                            builtInPluginEnabled.filterValues { it }.keys,
                            builtInToolDefinitions,
                        )
                    },
                ),
            )
            AgentEvent.SessionOpened(
                sessionId = SessionId(result.thread.id),
                model = result.model,
                effort = result.reasoningEffort,
                serviceTier = result.serviceTier,
            )
        } else {
            val result = connection.request(
                AppServerClientMethods.ThreadResume,
                ThreadResumeParams(
                    threadId = previous.value,
                    approvalPolicy = JsonPrimitive(settings.approvalPreset.approvalPolicy),
                    approvalsReviewer = approvalsReviewer(settings.approvalPreset),
                    config = config,
                    cwd = settings.workingDirectory,
                    developerInstructions = developerInstructions,
                    sandbox = SandboxMode.DANGER_FULL_ACCESS,
                    serviceTier = settings.serviceTier,
                ),
            )
            AgentEvent.SessionOpened(
                sessionId = SessionId(result.thread.id),
                model = result.model,
                effort = null,
                serviceTier = settings.serviceTier,
            )
        }
        val sessionId = opened.sessionId
        openedSessions += sessionId
        sessionRuntimeSettings[sessionId] = SessionRuntimeSettings(
            workspace = settings.workingDirectory,
            approvalPreset = settings.approvalPreset,
        )
        eventsChannel.send(opened)
        if (previous == null) {
            val original = builtInPluginEnabled.filterValues { it }.keys.toSet()
            val state = ThreadProviderState(original, original.associateWith { true })
            threadProviderStates[sessionId] = state
            runCatching { threadProviderStateStore.write(sessionId.value, state) }
        } else {
            val state = threadProviderStateStore.read(sessionId.value)
                ?: ThreadProviderState(emptySet(), emptyMap())
            threadProviderStates[sessionId] = state
            notifySessionOfPluginAvailability(sessionId)
        }
        return sessionId
    }

    override suspend fun sendTurn(sessionId: SessionId, request: AgentTurnRequest) {
        flushPluginAvailabilityNotice(sessionId)
        val snapshot = request.copy(
            capabilities = request.capabilities.toSet(),
            invocations = request.invocations.distinctBy(AgentInvocation::key),
        )
        require(
            snapshot.prompt.isNotBlank() || snapshot.capabilities.isNotEmpty() ||
                snapshot.invocations.isNotEmpty(),
        ) {
            "Prompt must not be blank"
        }
        require(snapshot.prompt.length <= MAX_PROMPT_CHARS) { "Prompt is too large" }
        require(snapshot.clientMessageId?.isNotBlank() != false) {
            "Client message ID must not be blank"
        }
        require(snapshot.model?.isNotBlank() != false) { "Model must not be blank" }
        require(snapshot.effort?.isNotBlank() != false) { "Effort must not be blank" }
        require(snapshot.serviceTier?.isNotBlank() != false) { "Service tier must not be blank" }
        require(snapshot.workingDirectory?.startsWith('/') != false) {
            "Working directory must be absolute"
        }
        synchronized(turnStateLock) {
            check(sessionId !in startingTurns && !activeTurns.containsKey(sessionId)) {
                "A turn is already active for this session"
            }
            cancelledTurns -= sessionId
            startingTurns += sessionId
        }
        val previousRuntimeSettings = sessionRuntimeSettings[sessionId]
        sessionRuntimeSettings[sessionId] = SessionRuntimeSettings(
            workspace = snapshot.workingDirectory ?: previousRuntimeSettings?.workspace,
            approvalPreset = snapshot.approvalPreset,
        )

        try {
            val result = connection.request(
                AppServerClientMethods.TurnStart,
                TurnStartParams(
                    input = turnInput(snapshot),
                    threadId = sessionId.value,
                    approvalPolicy = JsonPrimitive(snapshot.approvalPreset.approvalPolicy),
                    approvalsReviewer = approvalsReviewer(snapshot.approvalPreset),
                    clientUserMessageId = snapshot.clientMessageId,
                    cwd = snapshot.workingDirectory,
                    effort = snapshot.effort,
                    model = snapshot.model,
                    serviceTier = snapshot.serviceTier,
                ),
            )
            val turnId = result.turn.id
            synchronized(turnStateLock) {
                startingTurns -= sessionId
                if (terminalDuringStart.remove(sessionId) != turnId) {
                    activeTurns[sessionId] = turnId
                }
            }
        } catch (error: Exception) {
            if (previousRuntimeSettings == null) {
                sessionRuntimeSettings -= sessionId
            } else {
                sessionRuntimeSettings[sessionId] = previousRuntimeSettings
            }
            synchronized(turnStateLock) {
                startingTurns -= sessionId
                terminalDuringStart.remove(sessionId)
            }
            throw error
        }
    }

    override suspend fun runShellCommand(sessionId: SessionId, command: String) {
        val snapshot = command.trim()
        require(snapshot.isNotEmpty()) { "Shell command must not be blank" }
        require(snapshot.length <= MAX_PROMPT_CHARS) { "Shell command is too large" }
        check(sessionId in openedSessions) { "Session is not open" }
        synchronized(turnStateLock) {
            check(sessionId !in startingTurns && !activeTurns.containsKey(sessionId)) {
                "A turn is already active for this session"
            }
            startingTurns += sessionId
        }
        try {
            connection.request(
                AppServerClientMethods.ThreadShellCommand,
                ThreadShellCommandParams(command = snapshot, threadId = sessionId.value),
            )
        } finally {
            synchronized(turnStateLock) {
                startingTurns -= sessionId
                terminalDuringStart.remove(sessionId)
            }
        }
    }

    override suspend fun cancelTurn(sessionId: SessionId) {
        val turnId = synchronized(turnStateLock) {
            val active = activeTurns[sessionId] ?: error("No active turn for this session")
            check(cancellingTurns.add(sessionId)) { "Turn cancellation is already in progress" }
            cancelledTurns[sessionId] = active
            active
        }
        cancelPendingBuiltInTools(sessionId, turnId, "Built-in tool call was cancelled")
        try {
            try {
                connection.request(
                    AppServerClientMethods.TurnInterrupt,
                    TurnInterruptParams(threadId = sessionId.value, turnId = turnId),
                )
            } catch (error: AppServerRpcException) {
                if (error.code != -32600L || error.detail != "no active turn to interrupt") throw error
            }
        } finally {
            synchronized(turnStateLock) { cancellingTurns -= sessionId }
        }
    }

    override suspend fun resolveApproval(requestId: String, decision: AgentApprovalDecision) {
        pendingBuiltInApprovals.remove(requestId)?.let { pending ->
            if (decision == AgentApprovalDecision.ACCEPT) {
                pending.permit.set(true)
                executeBuiltInTool(pending)
            } else {
                respondBuiltInResult(
                    pending.wireId,
                    BuiltInToolResult.text("The user declined this built-in tool mutation.", false),
                )
            }
            return
        }
        val pending = pendingApprovalRequests.remove(requestId)
            ?: error("Approval request is no longer pending")
        val wireDecision = JsonPrimitive(decision.name.lowercase())
        when (pending.type) {
            ApprovalType.COMMAND -> connection.respond(
                pending.wireId,
                AppServerServerMethods.ItemCommandExecutionRequestApproval,
                CommandExecutionRequestApprovalResponse(wireDecision),
            )
            ApprovalType.FILE_CHANGE -> connection.respond(
                pending.wireId,
                AppServerServerMethods.ItemFileChangeRequestApproval,
                FileChangeRequestApprovalResponse(wireDecision),
            )
        }
    }

    override suspend fun resolveElicitation(
        requestId: String,
        response: AgentElicitationResponse,
    ) {
        val wireId = pendingElicitationRequests.remove(requestId)
            ?: error("Elicitation request is no longer pending")
        connection.respond(
            wireId,
            AppServerServerMethods.McpServerElicitationRequest,
            elicitationResponse(response),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingApprovalRequests.clear()
        pendingBuiltInApprovals.clear()
        pendingElicitationRequests.clear()
        workItems.clear()
        userShellItems.clear()
        knownSkillPaths.clear()
        openedSessions.clear()
        sessionRuntimeSettings.clear()
        pendingAvailabilityNotices.clear()
        threadProviderStates.clear()
        runBlocking { connection.shutdown() }
        scope.cancel()
        eventsChannel.close()
    }

    private suspend fun notifyOpenSessionsOfPluginAvailability() {
        openedSessions.forEach { notifySessionOfPluginAvailability(it) }
    }

    private suspend fun notifySessionOfPluginAvailability(sessionId: SessionId) {
        val state = threadProviderStates[sessionId] ?: return
        val availability = state.originalPluginIds.associateWith { builtInPluginEnabled[it] == true }
        val effectiveAvailability = pendingAvailabilityNotices[sessionId]?.availability ?: state.lastAvailability
        if (availability == effectiveAvailability) return
        sendPluginAvailabilityNotice(
            sessionId,
            PendingAvailabilityNotice(pluginAvailabilityNotice(availability), availability),
        )
    }

    private fun pluginAvailabilityNotice(availability: Map<String, Boolean>): String = buildString {
        append("Codex Mobile plugin availability changed. Current state: ")
        append(
            availability.entries.joinToString { (pluginId, enabled) ->
                "$pluginId=${if (enabled) "enabled" else "unavailable"}"
            },
        )
        append(". Use only enabled plugin tools that are registered in this thread. ")
        append("Do not rely on unavailable plugin skill instructions; continue the session normally.")
    }

    private suspend fun sendPluginAvailabilityNotice(sessionId: SessionId, pending: PendingAvailabilityNotice) {
        val notice = pending.text
        pendingAvailabilityNotices[sessionId] = pending
        val activeTurn = synchronized(turnStateLock) { activeTurns[sessionId] }
        val delivered = runCatching {
            if (activeTurn == null) {
                connection.request(
                    AppServerClientMethods.ThreadInjectItems,
                    ThreadInjectItemsParams(
                        items = listOf(
                            PROTOCOL_JSON.encodeToJsonElement(
                                ResponseItem.serializer(),
                                ResponseItemMessageResponseItem(
                                    content = listOf(ContentItemInputTextContentItem(notice)),
                                    role = "developer",
                                ),
                            ),
                        ),
                        threadId = sessionId.value,
                    ),
                )
            } else {
                connection.request(
                    AppServerClientMethods.TurnSteer,
                    TurnSteerParams(
                        expectedTurnId = activeTurn,
                        input = listOf(UserInputTextUserInput(notice)),
                        threadId = sessionId.value,
                        clientUserMessageId = "$AVAILABILITY_MESSAGE_PREFIX:${System.nanoTime()}",
                        additionalContext = mapOf(
                            "codex-mobile.plugin-availability" to AdditionalContextEntry(
                                kind = AdditionalContextKind.APPLICATION,
                                value = notice,
                            ),
                        ),
                    ),
                )
            }
        }.isSuccess
        if (delivered && pendingAvailabilityNotices.remove(sessionId, pending)) {
            val state = threadProviderStates[sessionId] ?: return
            val updated = state.copy(lastAvailability = pending.availability)
            threadProviderStates[sessionId] = updated
            runCatching { threadProviderStateStore.write(sessionId.value, updated) }
        }
    }

    private suspend fun flushPluginAvailabilityNotice(sessionId: SessionId) {
        pendingAvailabilityNotices[sessionId]?.let { sendPluginAvailabilityNotice(sessionId, it) }
    }

    private suspend fun refreshBuiltInPluginEnablement(workingDirectory: String) {
        if (builtInEnablementLoaded.get()) return
        builtInToolGate.withLock {
            if (builtInEnablementLoaded.get()) return
            runCatching {
                val result = connection.request(
                    AppServerClientMethods.PluginInstalled,
                    PluginInstalledParams(cwds = listOf(workingDirectory)),
                    PLUGIN_CATALOG_TIMEOUT_MILLIS,
                )
                applyBuiltInPluginEnablement(
                    AgentPluginCatalog(parsePluginMarketplaces(result.marketplaces), emptyList()),
                )
            }.onFailure {
                builtInPluginEnabled.keys.forEach { builtInPluginEnabled[it] = false }
            }
            builtInEnablementLoaded.set(true)
        }
    }

    private fun applyBuiltInPluginEnablement(catalog: AgentPluginCatalog) {
        builtInPluginEnabled.keys.forEach { pluginId ->
            val plugin = catalog.plugins.singleOrNull { it.reference.id == pluginId }
            builtInPluginEnabled[pluginId] = plugin?.let { it.installed && it.enabled } == true
        }
    }

    private suspend fun handleConnectionEvent(event: AppServerEvent) {
        when (event) {
            is AppServerEvent.Request -> handleServerRequest(event.value, event.descriptor.method)
            is AppServerEvent.Notification -> handleNotification(event.value)
            is AppServerEvent.Failure -> handleConnectionFailure(event.code, event.message)
        }
    }

    private suspend fun handleServerRequest(request: ServerRequest, method: String) {
        when (request) {
            is ServerRequestItemCommandExecutionRequestApprovalRequest -> handleApprovalRequest(
                request.id,
                request.params.threadId,
                request.params.reason,
                buildList {
                    request.params.command?.let { add("Command: $it") }
                    request.params.cwd?.let { add("Folder: $it") }
                },
                ApprovalType.COMMAND,
            )
            is ServerRequestItemFileChangeRequestApprovalRequest -> handleApprovalRequest(
                request.id,
                request.params.threadId,
                request.params.reason,
                buildList { request.params.grantRoot?.let { add("Folder: $it") } },
                ApprovalType.FILE_CHANGE,
            )
            is ServerRequestMcpServerElicitationRequestRequest ->
                handleElicitationRequest(request.id, request.params)
            is ServerRequestItemToolCallRequest -> handleBuiltInToolCall(request.id, request.params)
            else -> {
                val wire = PROTOCOL_JSON.encodeToJsonElement(ServerRequest.serializer(), request).jsonObject
                rejectServerRequest(wire.getValue("id"), method)
            }
        }
    }

    private fun handleBuiltInToolCall(id: JsonElement, params: DynamicToolCallParams) {
        val pending = runCatching {
            checkNotNull(builtInToolDispatcher) { "Built-in tools are unavailable" }
            check(params.namespace == null) { "Built-in tools do not use namespaces" }
            val tool = params.tool
            val definition = builtInToolsByName[tool] ?: error("Unknown built-in tool")
            val pluginId = definition.pluginId
            val sessionId = SessionId(params.threadId)
            check(sessionId in openedSessions) { "Tool call session is not open" }
            val runtimeSettings = sessionRuntimeSettings[sessionId]
                ?: error("Tool call session settings are unavailable")
            val workspace = runtimeSettings.workspace
                ?: error("A selected Android workspace is required")
            val arguments = params.arguments as? JsonObject
                ?: error("Tool arguments must be an object")
            val call = BuiltInToolCall(
                threadId = sessionId.value,
                turnId = params.turnId,
                callId = params.callId,
                pluginId = pluginId,
                tool = tool,
                arguments = arguments,
                workspace = workspace,
                argumentsHash = sha256(canonicalJson(arguments)),
                deadlineEpochMillis = System.currentTimeMillis() + BUILT_IN_TOOL_DEADLINE_MILLIS,
            )
            PendingBuiltInApproval(
                wireId = id,
                call = call,
                requiresPermit = definition.mutation &&
                    typedMutationAuthority(runtimeSettings.approvalPreset) ==
                    TypedMutationAuthority.USER_APPROVAL,
            )
        }.getOrElse { error ->
            scope.launch {
                respondBuiltInResult(id, BuiltInToolResult.text(error.visibleMessage(), false))
            }
            return
        }

        scope.launch { continueBuiltInToolCall(pending) }
    }

    private suspend fun continueBuiltInToolCall(pending: PendingBuiltInApproval) {
        val replay = try {
            builtInToolGate.withLock {
                validateBuiltInCall(pending)
                checkNotNull(builtInToolDispatcher).replay(pending.call)
            }
        } catch (error: Exception) {
            respondBuiltInResult(pending.wireId, BuiltInToolResult.text(error.visibleMessage(), false))
            return
        }
        if (replay != null) {
            respondBuiltInResult(pending.wireId, replay)
            return
        }

        val runtimeSettings = sessionRuntimeSettings[SessionId(pending.call.threadId)]
            ?: return respondBuiltInResult(
                pending.wireId,
                BuiltInToolResult.text("Tool call session settings are unavailable", false),
            )
        if (builtInToolsByName[pending.call.tool]?.mutation == true) {
            when (typedMutationAuthority(runtimeSettings.approvalPreset)) {
                TypedMutationAuthority.UNAVAILABLE -> {
                    respondBuiltInResult(
                        pending.wireId,
                        BuiltInToolResult.text(
                            "This typed mutation is unavailable under Auto review because app-server 0.144.6 " +
                                "does not expose an equivalent automatic-review bridge.",
                            false,
                        ),
                    )
                    return
                }
                TypedMutationAuthority.USER_APPROVAL -> {
                    val call = pending.call
                    val requestId = "builtin:${call.threadId}:${call.turnId}:${call.callId}"
                    if (pendingBuiltInApprovals.putIfAbsent(requestId, pending) != null) {
                        respondBuiltInResult(
                            pending.wireId,
                            BuiltInToolResult.text("Duplicate approval request", false),
                        )
                        return
                    }
                    eventsChannel.send(
                        AgentEvent.ApprovalRequested(
                            sessionId = SessionId(call.threadId),
                            requestId = requestId,
                            title = "Approve ${call.tool.replace('_', ' ')}?",
                            details = "Plugin: ${call.pluginId}\nWorkspace: ${call.workspace}",
                        ),
                    )
                    return
                }
                TypedMutationAuthority.DIRECT -> Unit
            }
        }
        executeBuiltInTool(pending)
    }

    private suspend fun executeBuiltInTool(pending: PendingBuiltInApproval) {
        val result = runCatching {
            builtInToolGate.withLock {
                validateBuiltInCall(pending)
                checkNotNull(builtInToolDispatcher).execute(
                    pending.call,
                    checkActive = { validateBuiltInCall(pending) },
                    beforeMutationDispatch = {
                        validateBuiltInCall(pending)
                        check(pending.dispatch.compareAndSet(false, true)) {
                            "Built-in mutation dispatch was already used"
                        }
                        if (pending.requiresPermit) {
                            check(pending.permit.compareAndSet(true, false)) {
                                "Built-in mutation approval is missing or was already used"
                            }
                        }
                    },
                )
            }
        }.getOrElse { error -> BuiltInToolResult.text(error.visibleMessage(), false) }
        runCatching { respondBuiltInResult(pending.wireId, result) }
    }

    private fun validateBuiltInCall(pending: PendingBuiltInApproval) {
        val call = pending.call
        check(builtInPluginEnabled[call.pluginId] == true) { "${call.pluginId} is disabled" }
        check(System.currentTimeMillis() <= call.deadlineEpochMillis) {
            "Built-in tool call deadline expired"
        }
        val sessionId = SessionId(call.threadId)
        val active = synchronized(turnStateLock) {
            (activeTurns[sessionId] == call.turnId || sessionId in startingTurns) &&
                cancelledTurns[sessionId] != call.turnId
        }
        check(active) { "Built-in tool call is no longer active" }
    }

    private fun cancelPendingBuiltInTools(sessionId: SessionId, turnId: String?, message: String) {
        pendingBuiltInApprovals.entries
            .filter { (_, pending) ->
                pending.call.threadId == sessionId.value &&
                    (turnId == null || pending.call.turnId == turnId)
            }
            .forEach { (requestId, pending) ->
                if (pendingBuiltInApprovals.remove(requestId, pending)) {
                    scope.launch {
                        runCatching {
                            respondBuiltInResult(pending.wireId, BuiltInToolResult.text(message, false))
                        }
                    }
                }
            }
    }

    private suspend fun respondBuiltInResult(id: JsonElement, result: BuiltInToolResult) {
        connection.respond(
            id,
            AppServerServerMethods.ItemToolCall,
            DynamicToolCallResponse(
                contentItems = result.content.map { item ->
                    when (item) {
                        is ProviderContent.Text ->
                            DynamicToolCallOutputContentItemInputTextDynamicToolCallOutputContentItem(
                                item.value.take(MAX_BUILT_IN_RESULT_CHARS),
                            )
                        is ProviderContent.Image -> {
                            check(item.dataUrl.startsWith("data:image/")) {
                                "Built-in images must use inline data URLs"
                            }
                            DynamicToolCallOutputContentItemInputImageDynamicToolCallOutputContentItem(
                                item.dataUrl,
                            )
                        }
                    }
                },
                success = result.success,
            ),
        )
    }

    private suspend fun handleElicitationRequest(
        id: JsonElement,
        params: McpServerElicitationRequestParams,
    ) {
        val elicitation = runCatching {
            val requestId = id.toString()
            val parsed = parseElicitation(requestId, params)
            check(parsed.sessionId in openedSessions) { "Elicitation session is not open" }
            check(pendingElicitationRequests.putIfAbsent(requestId, id) == null) {
                "Elicitation request ID is already pending"
            }
            parsed
        }.getOrElse {
            connection.respond(
                id,
                AppServerServerMethods.McpServerElicitationRequest,
                McpServerElicitationRequestResponse(McpServerElicitationAction.DECLINE),
            )
            return
        }
        eventsChannel.send(AgentEvent.ElicitationRequested(elicitation))
    }

    private suspend fun handleApprovalRequest(
        id: JsonElement,
        threadId: String,
        reason: String?,
        detailLines: List<String>,
        type: ApprovalType,
    ) {
        val event = runCatching {
            val sessionId = SessionId(threadId)
            check(sessionId in openedSessions) { "Approval session is not open" }
            val requestId = id.toString()
            check(pendingApprovalRequests.putIfAbsent(requestId, PendingApproval(id, type)) == null) {
                "Approval request ID is already pending"
            }
            val title = if (type == ApprovalType.FILE_CHANGE) {
                "Approve file changes?"
            } else {
                "Approve command?"
            }
            val details = buildList {
                reason?.let(::add)
                addAll(detailLines)
            }.joinToString("\n").ifBlank { "Codex requested permission to continue." }
            AgentEvent.ApprovalRequested(sessionId, requestId, title, details)
        }.getOrElse {
            respondServerError(id, -32602, "Invalid approval request")
            return
        }
        eventsChannel.send(event)
    }

    private suspend fun rejectServerRequest(id: JsonElement, method: String) {
        respondServerError(id, -32601, "Client method is not available: $method")
    }

    private suspend fun respondServerError(id: JsonElement, code: Int, message: String) =
        connection.respondError(id, code.toLong(), message)

    private suspend fun handleNotification(notification: ServerNotification) {
        when (notification) {
            is ServerNotificationAccountLoginCompletedNotification -> {
                val params = notification.params
                val completion = LoginCompletion(
                    loginId = params.loginId ?: error("Login completion ID is missing"),
                    success = params.success,
                    error = params.error,
                )
                val applyNow = synchronized(loginStateLock) {
                    if (cancelledLoginIds.remove(completion.loginId)) {
                        if (loginId == completion.loginId) loginId = null
                        false
                    } else if (loginStarting) {
                        loginCompletedDuringStart = completion
                        false
                    } else if (loginId == completion.loginId) {
                        loginId = null
                        true
                    } else {
                        false
                    }
                }
                if (applyNow) applyLoginCompletion(completion)
            }

            is ServerNotificationAccountUpdatedNotification -> {
                if (notification.params.authMode?.jsonPrimitive?.contentOrNull == "chatgpt") {
                    emitAuthenticated()
                }
            }

            is ServerNotificationSkillsChangedNotification ->
                eventsChannel.send(AgentEvent.SkillsChanged)

            is ServerNotificationAppListUpdatedNotification ->
                eventsChannel.send(AgentEvent.ConnectorsChanged)

            is ServerNotificationMcpServerOauthLoginCompletedNotification -> eventsChannel.send(
                AgentEvent.McpOauthCompleted(
                    serverName = notification.params.name,
                    success = notification.params.success,
                    error = notification.params.error,
                ),
            )

            is ServerNotificationItemAgentMessageDeltaNotification -> {
                val params = notification.params
                val sessionId = SessionId(params.threadId)
                eventsChannel.send(
                    AgentEvent.TextDelta(
                        sessionId = sessionId,
                        text = params.delta,
                        itemId = params.itemId,
                    ),
                )
            }

            is ServerNotificationItemCommandExecutionOutputDeltaNotification -> {
                val params = notification.params
                if (params.itemId in userShellItems) {
                    eventsChannel.send(
                        AgentEvent.ShellOutputDelta(
                            sessionId = SessionId(params.threadId),
                            text = params.delta,
                        ),
                    )
                }
            }

            is ServerNotificationItemStartedNotification -> updateItemActivity(
                notification.params.threadId,
                notification.params.turnId,
                notification.params.item,
                started = true,
            )

            is ServerNotificationItemCompletedNotification -> {
                val params = notification.params
                completeUserShellItem(params.threadId, params.item)
                updateItemActivity(params.threadId, params.turnId, params.item, started = false)
            }

            is ServerNotificationTurnCompletedNotification -> {
                val params = notification.params
                val sessionId = SessionId(params.threadId)
                finishTurn(sessionId, params.turn.id)
                if (params.turn.status == TurnStatus.FAILED) {
                    val detail = params.turn.error?.message ?: "Turn failed"
                    eventsChannel.send(AgentEvent.Failure(sessionId, "turn_failed", detail, true))
                } else {
                    eventsChannel.send(AgentEvent.TurnCompleted(sessionId))
                }
            }

            is ServerNotificationErrorNotification -> {
                val params = notification.params
                if (!params.willRetry) {
                    val sessionId = SessionId(params.threadId)
                    finishTurn(sessionId, params.turnId)
                    eventsChannel.send(
                        AgentEvent.Failure(sessionId, "turn_error", params.error.message, true),
                    )
                }
            }

            else -> Unit
        }
    }

    private suspend fun emitAuthenticated() {
        if (authenticated.compareAndSet(false, true)) eventsChannel.send(AgentEvent.Authenticated)
    }

    private suspend fun applyLoginCompletion(completion: LoginCompletion) {
        if (completion.success) {
            emitAuthenticated()
        } else {
            eventsChannel.send(
                AgentEvent.Failure(
                    null,
                    "authentication_failed",
                    completion.error ?: "Authentication failed",
                    recoverable = true,
                ),
            )
        }
    }

    private suspend fun finishTurn(sessionId: SessionId, turnId: String?) {
        synchronized(turnStateLock) {
            if (turnId == null || activeTurns[sessionId] == turnId) activeTurns.remove(sessionId)
            if (turnId != null && sessionId in startingTurns) {
                terminalDuringStart[sessionId] = turnId
            }
            cancellingTurns -= sessionId
            if (turnId == null || cancelledTurns[sessionId] == turnId) cancelledTurns -= sessionId
        }
        cancelPendingBuiltInTools(sessionId, turnId, "Built-in tool call is no longer active")
        val removedWork = workItems.entries.removeIf { it.value.first == sessionId }
        if (removedWork) eventsChannel.send(AgentEvent.WorkActivityChanged(sessionId, null))
        if (pendingAvailabilityNotices.containsKey(sessionId)) {
            scope.launch { flushPluginAvailabilityNotice(sessionId) }
        }
    }

    private suspend fun updateItemActivity(
        threadId: String,
        turnId: String,
        item: ThreadItem,
        started: Boolean,
    ) {
        val sessionId = SessionId(threadId)
        val itemId = when (item) {
            is ThreadItemCommandExecutionThreadItem -> item.id
            is ThreadItemFileChangeThreadItem -> item.id
            else -> return
        }
        if (
            started && item is ThreadItemCommandExecutionThreadItem &&
            item.source == CommandExecutionSource.USER_SHELL
        ) {
            userShellItems += itemId
            synchronized(turnStateLock) { activeTurns[sessionId] = turnId }
        }
        val activity = when (item) {
            is ThreadItemCommandExecutionThreadItem -> AgentWorkActivity.RUNNING_COMMAND
            is ThreadItemFileChangeThreadItem -> AgentWorkActivity.WRITING_FILES
        }
        if (started) {
            workItems[itemId] = sessionId to activity
            eventsChannel.send(AgentEvent.WorkActivityChanged(sessionId, activity))
        } else if (!started && workItems.remove(itemId) != null) {
            eventsChannel.send(
                AgentEvent.WorkActivityChanged(
                    sessionId,
                    workItems.values.lastOrNull { it.first == sessionId }?.second,
                ),
            )
        }
    }

    private suspend fun completeUserShellItem(threadId: String, item: ThreadItem) {
        if (item !is ThreadItemCommandExecutionThreadItem) return
        if (userShellItems.remove(item.id)) {
            eventsChannel.send(
                AgentEvent.ShellCommandCompleted(
                    sessionId = SessionId(threadId),
                    exitCode = item.exitCode?.toInt(),
                ),
            )
        }
    }

    private suspend fun handleConnectionFailure(code: String, message: String) {
        authenticated.set(false)
        builtInEnablementLoaded.set(false)
        synchronized(loginStateLock) {
            loginId = null
            loginStarting = false
            loginCompletedDuringStart = null
            cancelledLoginIds.clear()
        }
        synchronized(turnStateLock) {
            activeTurns.clear()
            startingTurns.clear()
            terminalDuringStart.clear()
            cancellingTurns.clear()
            cancelledTurns.clear()
        }
        pendingApprovalRequests.clear()
        pendingBuiltInApprovals.clear()
        pendingElicitationRequests.clear()
        workItems.clear()
        userShellItems.clear()
        openedSessions.clear()
        sessionRuntimeSettings.clear()
        pendingAvailabilityNotices.clear()
        threadProviderStates.clear()
        eventsChannel.send(AgentEvent.Failure(null, code, message, recoverable = true))
    }

    private suspend fun <P, R, T, U> requestAllPages(
        method: AppServerMethod<P, R>,
        params: (String?) -> P,
        data: (R) -> List<T>,
        nextCursor: (R) -> String?,
        transform: (T) -> U,
    ): List<U> {
        val values = mutableListOf<U>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = connection.request(method, params(cursor))
            values += data(page).map(transform)
            cursor = nextCursor(page)
            check(cursor == null || seenCursors.add(cursor)) { "App-server repeated a pagination cursor" }
        } while (cursor != null)
        return values
    }

    private fun pluginReadParams(plugin: AgentPluginReference) = PluginReadParams(
        pluginName = plugin.name,
        marketplacePath = plugin.marketplacePath,
        remoteMarketplaceName = plugin.marketplaceName.takeIf { plugin.marketplacePath == null },
    )

    private fun pluginInstallParams(plugin: AgentPluginReference) = PluginInstallParams(
        pluginName = plugin.name,
        marketplacePath = plugin.marketplacePath,
        remoteMarketplaceName = plugin.marketplaceName.takeIf { plugin.marketplacePath == null },
    )

    private fun pluginEnablementParams(pluginId: String, enabled: Boolean) = ConfigValueWriteParams(
        keyPath = "plugins.$pluginId.enabled",
        value = JsonPrimitive(enabled),
        mergeStrategy = MergeStrategy.UPSERT,
    )

    private fun approvalsReviewer(preset: AgentApprovalPreset) = when (preset) {
        AgentApprovalPreset.AUTO_REVIEW -> ApprovalsReviewer.AUTO_REVIEW
        else -> ApprovalsReviewer.USER
    }

    private suspend fun disableManagedProviderMcp(pluginId: String) {
        providerHost?.mcpServerNames(pluginId).orEmpty().forEach { serverName ->
            connection.request(
                AppServerClientMethods.ConfigValueWrite,
                ConfigValueWriteParams(
                    keyPath = "mcp_servers.$serverName",
                    value = JsonNull,
                    mergeStrategy = MergeStrategy.UPSERT,
                ),
            )
            connection.request(
                AppServerClientMethods.ConfigValueWrite,
                ConfigValueWriteParams(
                    keyPath = "plugins.$pluginId.mcp_servers.$serverName.enabled",
                    value = JsonPrimitive(false),
                    mergeStrategy = MergeStrategy.UPSERT,
                ),
            )
        }
    }

    private fun refreshBuiltInTools() {
        val definitions = builtInToolDispatcher?.definitions().orEmpty()
        builtInToolDefinitions = definitions
        builtInToolsByName = definitions.associateBy(BuiltInToolDefinition::name)
        definitions.map(BuiltInToolDefinition::pluginId).forEach { builtInPluginEnabled.putIfAbsent(it, true) }
    }

    private suspend fun completePendingProviderInstalls() {
        val host = providerHost ?: return
        host.pendingInstalls().forEach { plugin ->
            val detail = readPlugin(plugin)
            check(detail.mcpServers.toSet() == host.mcpServerNames(plugin.id)) {
                "Provider MCP configuration changed before activation"
            }
            disableManagedProviderMcp(plugin.id)
            if (!detail.summary.installed) {
                connection.request(AppServerClientMethods.PluginInstall, pluginInstallParams(plugin))
            }
            host.installCompleted(plugin.id)
        }
    }

    private suspend fun completePreparedProviderRemovals(catalog: AgentPluginCatalog) {
        val host = providerHost ?: return
        val installed = catalog.plugins.filter { it.installed }.map { it.reference.id }.toSet()
        host.preparedRemovals().forEach { plugin ->
            if (plugin.id in installed) {
                connection.request(
                    AppServerClientMethods.PluginUninstall,
                    PluginUninstallParams(plugin.id),
                )
            }
            host.remove(plugin.id)
        }
    }

    private fun reconcileProvidersInBackground(catalog: AgentPluginCatalog) {
        val host = providerHost ?: return
        if (host.pendingInstalls().isEmpty() && host.preparedRemovals().isEmpty()) return
        if (!pendingProviderCompletionRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                completePendingProviderInstalls()
                completePreparedProviderRemovals(catalog)
                eventsChannel.send(AgentEvent.PluginsChanged)
            } finally {
                pendingProviderCompletionRunning.set(false)
            }
        }
    }

    private fun parseGitHubMarketplaceSource(value: String): MarketplaceSource {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true)) {
            "Use a public https://github.com repository URL"
        }
        require(uri.query == null && uri.fragment == null && uri.userInfo == null) { "Invalid GitHub repository URL" }
        val segments = uri.path.trim('/').split('/').filter(String::isNotBlank)
        require(segments.size >= 2 && segments.take(2).all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
            "Use a GitHub repository or tree URL"
        }
        val repository = segments[1].removeSuffix(".git")
        require(repository.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))) { "Invalid repository name" }
        val repositoryUrl = "https://github.com/${segments[0]}/$repository.git"
        if (segments.size == 2) return MarketplaceSource(repositoryUrl)
        require(segments.size >= 4 && segments[2] == "tree") {
            "Use a GitHub repository or tree URL"
        }
        val refName = segments[3]
        require(refName.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid Git reference" }
        val sparsePaths = segments.drop(4).takeIf { it.isNotEmpty() }?.let { path ->
            require(path.all { it.matches(Regex("[A-Za-z0-9._-]+")) }) { "Invalid repository path" }
            listOf(path.joinToString("/"))
        }
        return MarketplaceSource(repositoryUrl, refName, sparsePaths)
    }

    private fun AppServerRpcException.forPlugin(plugin: AgentPluginReference): Throwable =
        if (detail.contains("Plugin not found", ignoreCase = true) ||
            detail.contains("status 404", ignoreCase = true) && detail.contains("/plugins/")) {
            AgentPluginUnavailableException(
                plugin.id,
                plugin.name.replace('-', ' ').replaceFirstChar(Char::uppercase),
            )
        } else {
            this
        }

    private fun elicitationResponse(response: AgentElicitationResponse): McpServerElicitationRequestResponse {
        val content = if (response.action == AgentElicitationAction.ACCEPT) {
            buildJsonObject {
                response.content.forEach { (name, value) ->
                    put(
                        name,
                        when (value) {
                            is AgentFormValue.Text -> JsonPrimitive(value.value)
                            is AgentFormValue.Number -> JsonPrimitive(value.value)
                            is AgentFormValue.BooleanValue -> JsonPrimitive(value.value)
                            is AgentFormValue.TextList -> JsonArray(value.value.map(::JsonPrimitive))
                        },
                    )
                }
            }
        } else {
            null
        }
        return McpServerElicitationRequestResponse(
            action = when (response.action) {
                AgentElicitationAction.ACCEPT -> McpServerElicitationAction.ACCEPT
                AgentElicitationAction.DECLINE -> McpServerElicitationAction.DECLINE
                AgentElicitationAction.CANCEL -> McpServerElicitationAction.CANCEL
            },
            content = content,
        )
    }

    private data class LoginCompletion(
        val loginId: String,
        val success: Boolean,
        val error: String?,
    )

    private data class SessionRuntimeSettings(
        val workspace: String?,
        val approvalPreset: io.github.ciurlaro.codexmobile.core.AgentApprovalPreset,
    )

    private data class PendingBuiltInApproval(
        val wireId: JsonElement,
        val call: BuiltInToolCall,
        val requiresPermit: Boolean,
        val permit: AtomicBoolean = AtomicBoolean(),
        val dispatch: AtomicBoolean = AtomicBoolean(),
    )

    private data class PendingApproval(val wireId: JsonElement, val type: ApprovalType)

    private enum class ApprovalType { COMMAND, FILE_CHANGE }

    private data class PendingAvailabilityNotice(
        val text: String,
        val availability: Map<String, Boolean>,
    )

    private data class MarketplaceSource(
        val repository: String,
        val refName: String? = null,
        val sparsePaths: List<String>? = null,
    )

    private companion object {
        val PROTOCOL_JSON = Json {
            encodeDefaults = true
            explicitNulls = false
        }
        const val EVENT_BUFFER_SIZE = 64
        const val MAX_PROMPT_CHARS = 100_000
        const val MAX_BUILT_IN_RESULT_CHARS = 250_000
        const val BUILT_IN_TOOL_DEADLINE_MILLIS = 120_000L
        const val SKILL_CHUNK_BYTES = 32 * 1024
        const val PLUGIN_CATALOG_TIMEOUT_MILLIS = 20_000L
        const val CATALOG_CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L
        const val AVAILABILITY_MESSAGE_PREFIX = "codex-mobile:plugin-availability"
        const val INTERNAL_APPS_MCP_SERVER = "codex_apps"

        fun completeUtf8Length(bytes: ByteArray, count: Int): Int {
            if (count == 0) return 0
            var lead = count - 1
            while (lead >= 0 && bytes[lead].toInt() and 0xC0 == 0x80) lead--
            if (lead < 0) return 0
            val expected = when (bytes[lead].toInt() and 0xFF) {
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> 1
            }
            return if (count - lead < expected) lead else count
        }
    }
}

private fun Throwable.visibleMessage(): String =
    message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"
