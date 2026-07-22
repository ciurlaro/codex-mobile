package io.github.ciurlaro.codexmobile.agent.codex

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
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentServiceTier
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkillChunk
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    private val builtInToolDispatcher: BuiltInToolDispatcher? = null,
) : AgentClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventsChannel = Channel<AgentEvent>(capacity = EVENT_BUFFER_SIZE)
    private val authMutex = Mutex()
    private val loginStateLock = Any()
    private val cancelledLoginIds = mutableSetOf<String>()
    private val pendingApprovalRequests = ConcurrentHashMap<String, JsonElement>()
    private val pendingBuiltInApprovals = ConcurrentHashMap<String, PendingBuiltInApproval>()
    private val pendingElicitationRequests = ConcurrentHashMap<String, JsonElement>()
    private val workItems = ConcurrentHashMap<String, Pair<SessionId, AgentWorkActivity>>()
    private val userShellItems = ConcurrentHashMap.newKeySet<String>()
    private val knownSkillPaths = ConcurrentHashMap.newKeySet<String>()
    private val openedSessions = ConcurrentHashMap.newKeySet<SessionId>()
    private val sessionRuntimeSettings = ConcurrentHashMap<SessionId, SessionRuntimeSettings>()
    private val builtInPluginEnabled = ConcurrentHashMap<String, Boolean>().apply {
        put(DOCUMENTS_PLUGIN_ID, true)
        put(TELEGRAM_PLUGIN_ID, true)
    }
    private val builtInToolGate = Mutex()
    private val builtInEnablementLoaded = AtomicBoolean(false)
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
        clientVersion = clientVersion,
        requestTimeoutMillis = requestTimeoutMillis,
        onServerRequest = ::handleServerRequest,
        onNotification = ::handleNotification,
        onFailure = ::handleConnectionFailure,
    )

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

        val account = request(
            "account/read",
            buildJsonObject { put("refreshToken", false) },
        ).jsonObject["account"]
        if (account is JsonObject && account["type"]?.jsonPrimitive?.contentOrNull == "chatgpt") {
            emitAuthenticated()
            return@withLock
        }

        synchronized(loginStateLock) {
            loginStarting = true
            loginCompletedDuringStart = null
        }
        try {
            val result = request(
                "account/login/start",
                buildJsonObject {
                    put("type", "chatgpt")
                    put("useHostedLoginSuccessPage", true)
                    put("appBrand", "codex")
                },
            ).jsonObject
            val startedLoginId = result.requiredString("loginId")
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
                        signInUrl = result.requiredString("authUrl"),
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
            val status = request(
                "account/login/cancel",
                buildJsonObject { put("loginId", activeLoginId) },
            ).jsonObject.requiredString("status")
            check(status == "canceled" || status == "notFound") {
                "Unexpected login cancellation status: $status"
            }
            synchronized(loginStateLock) {
                if (loginId == activeLoginId) loginId = null
                if (status == "notFound") cancelledLoginIds -= activeLoginId
            }
        } catch (error: Exception) {
            synchronized(loginStateLock) { cancelledLoginIds -= activeLoginId }
            throw error
        }
    }

    override suspend fun signOut() = authMutex.withLock {
        connection.ensureStarted()
        request("account/logout", buildJsonObject {})
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

    override suspend fun listModels(): List<AgentModel> = requestAllPages("model/list") { item ->
        val serviceTiers = (
            item.optionalArray("serviceTiers") + item.optionalArray("additionalSpeedTiers")
            ).mapNotNull { raw ->
                val tier = raw as? JsonObject ?: return@mapNotNull null
                val id = tier.optionalString("id") ?: tier.optionalString("serviceTier")
                    ?: return@mapNotNull null
                AgentServiceTier(
                    id = id,
                    name = tier.optionalString("name") ?: id.replaceFirstChar { it.uppercase() },
                    description = tier.optionalString("description").orEmpty(),
                )
            }.distinctBy(AgentServiceTier::id)
        AgentModel(
            id = item.requiredString("model"),
            displayName = item.requiredString("displayName"),
            description = item.requiredString("description"),
            supportedEfforts = item.requiredArray("supportedReasoningEfforts").map { effort ->
                effort.jsonObject.requiredString("reasoningEffort")
            },
            defaultEffort = item.requiredString("defaultReasoningEffort"),
            isDefault = item.requiredBoolean("isDefault"),
            serviceTiers = serviceTiers,
            defaultServiceTier = item.optionalString("defaultServiceTier"),
        )
    }

    override suspend fun listSkills(
        workingDirectory: String,
        forceReload: Boolean,
    ): AgentSkillCatalog {
        require(workingDirectory.startsWith('/')) { "Working directory must be absolute" }
        val result = request(
            "skills/list",
            buildJsonObject {
                put("cwds", buildJsonArray { add(JsonPrimitive(workingDirectory)) })
                put("forceReload", forceReload)
            },
        ).jsonObject
        val entries = result.requiredArray("data").map(JsonElement::jsonObject)
        return AgentSkillCatalog(
            skills = entries.flatMap { it.requiredArray("skills") }.map { parseSkill(it.jsonObject) }
                .distinctBy { it.path },
            errors = entries.flatMap { it.requiredArray("errors") }.map { error ->
                error.jsonObject.let { "${it.requiredString("path")}: ${it.requiredText("message")}" }
            },
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
        request(
            "skills/config/write",
            buildJsonObject {
                put("path", path)
                put("enabled", enabled)
            },
        )
    }

    override suspend fun listInstalledPlugins(workingDirectory: String): AgentPluginCatalog =
        listPlugins(workingDirectory, "plugin/installed")

    override suspend fun listAvailablePlugins(
        workingDirectory: String,
        forceRefresh: Boolean,
    ): AgentPluginCatalog {
        require(workingDirectory.startsWith('/')) { "Working directory must be absolute" }
        val cache = pluginCacheFile(workingDirectory)
        if (!forceRefresh) readPluginCache(cache)?.takeIf { it.plugins.isNotEmpty() }?.let { return it }
        return runCatching {
            var catalog = listPlugins(workingDirectory, "plugin/list", PLUGIN_CATALOG_TIMEOUT_MILLIS) {
                writePluginCache(cache, it)
            }
            for (retryDelay in EMPTY_PLUGIN_CATALOG_RETRY_DELAYS_MILLIS) {
                if (catalog.plugins.isNotEmpty() || catalog.errors.isNotEmpty()) break
                delay(retryDelay)
                catalog = listPlugins(workingDirectory, "plugin/list", PLUGIN_CATALOG_TIMEOUT_MILLIS) {
                    writePluginCache(cache, it)
                }
            }
            if (catalog.plugins.isEmpty() && catalog.errors.isEmpty()) catalog.copy(
                errors = listOf("The plugin marketplace is not ready yet. Retry in a moment."),
            ) else catalog
        }.getOrElse { error ->
            readPluginCache(cache, stale = true)?.takeIf { it.plugins.isNotEmpty() }?.copy(
                errors = listOfNotNull(error.message ?: "Available plugins could not be refreshed"),
            ) ?: throw error
        }
    }

    private suspend fun listPlugins(
        workingDirectory: String,
        method: String,
        timeoutMillis: Long? = null,
        onResponse: (JsonObject) -> Unit = {},
    ): AgentPluginCatalog {
        require(workingDirectory.startsWith('/')) { "Working directory must be absolute" }
        val params = buildJsonObject {
            put("cwds", buildJsonArray { add(JsonPrimitive(workingDirectory)) })
        }
        val result = request(method, params, timeoutMillis).jsonObject
        val errors = result.optionalArray("marketplaceLoadErrors").mapNotNull { raw ->
            raw.jsonObject.optionalString("message")
        }.distinct()
        val catalog = AgentPluginCatalog(parsePluginMarketplaces(result), errors)
        if (builtInToolDispatcher != null) {
            builtInToolGate.withLock {
                applyBuiltInPluginEnablement(catalog)
                builtInEnablementLoaded.set(true)
            }
        }
        if (catalog.plugins.isNotEmpty()) runCatching { onResponse(result) }
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
            val result = Json.parseToJsonElement(file.readText()).jsonObject
            val freshness = if (!stale && System.currentTimeMillis() - file.lastModified() <= CATALOG_CACHE_TTL_MILLIS) {
                AgentCatalogFreshness.FRESH_CACHE
            } else {
                AgentCatalogFreshness.STALE_CACHE
            }
            AgentPluginCatalog(
                plugins = parsePluginMarketplaces(result),
                errors = result.optionalArray("marketplaceLoadErrors").mapNotNull {
                    it.jsonObject.optionalString("message")
                }.distinct(),
                freshness = freshness,
            )
        }.getOrNull()
    }

    private fun writePluginCache(file: File?, response: JsonObject) {
        if (file == null) return
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Unable to prepare plugin catalog cache"
        }
        val next = File(file.parentFile, ".${file.name}.next")
        next.writeText(response.toString())
        check((!file.exists() || file.delete()) && next.renameTo(file)) {
            "Unable to update plugin catalog cache"
        }
    }

    override suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail {
        return try {
            parsePluginDetail(request("plugin/read", pluginParams(plugin)).jsonObject)
        } catch (error: RpcException) {
            throw error.forPlugin(plugin)
        }
    }

    override suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult {
        val result = try {
            request("plugin/install", pluginParams(plugin)).jsonObject
        } catch (error: RpcException) {
            throw error.forPlugin(plugin)
        }
        return AgentPluginInstallResult(
            authPolicy = enumValueOf(result.requiredString("authPolicy")),
            connectorsNeedingAuthentication = result.requiredArray("appsNeedingAuth").map {
                parseConnector(it.jsonObject)
            },
        )
    }

    override suspend fun uninstallPlugin(pluginId: String) {
        require(pluginId.isNotBlank()) { "Plugin ID must not be blank" }
        require(!builtInPluginEnabled.containsKey(pluginId)) { "Built-in plugins cannot be uninstalled" }
        request("plugin/uninstall", buildJsonObject { put("pluginId", pluginId) })
    }

    override suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        require(pluginId.isNotBlank() && '.' !in pluginId) { "Invalid plugin ID" }
        if (builtInPluginEnabled.containsKey(pluginId)) {
            builtInToolGate.withLock {
                request(
                    "config/value/write",
                    pluginEnablementParams(pluginId, enabled),
                )
                builtInPluginEnabled[pluginId] = enabled
            }
        } else {
            request("config/value/write", pluginEnablementParams(pluginId, enabled))
        }
    }

    override suspend fun listConnectors(
        sessionId: SessionId?,
        forceReload: Boolean,
    ): List<AgentConnector> = requestAllPages(
        "app/list",
        buildJsonObject {
            sessionId?.let { put("threadId", it.value) }
            put("forceRefetch", forceReload)
        },
        ::parseConnector,
    )

    override suspend fun listMcpServers(): List<AgentMcpServer> =
        requestAllPages("mcpServerStatus/list", transform = ::parseMcpServer)

    override suspend fun startMcpOauth(serverName: String, sessionId: SessionId?): String {
        require(serverName.isNotBlank()) { "MCP server name must not be blank" }
        return request(
            "mcpServer/oauth/login",
            buildJsonObject {
                put("name", serverName)
                sessionId?.let { put("threadId", it.value) }
            },
        ).jsonObject.requiredString("authorizationUrl").also(::requireSafeAuthUrl)
    }

    override suspend fun listSessions(): List<AgentConversationSummary> = requestAllPages(
        "thread/list",
        buildJsonObject {
            put("sortKey", "updated_at")
            put("sortDirection", "desc")
        },
        ::conversationSummary,
    )

    override suspend fun readSession(sessionId: SessionId): AgentConversation {
        val thread = request(
            "thread/read",
            buildJsonObject {
                put("threadId", sessionId.value)
                put("includeTurns", true)
            },
        ).jsonObject.requiredObject("thread")
        check(thread.requiredString("id") == sessionId.value) { "App-server returned another thread" }
        val messages = thread.requiredArray("turns").flatMap { turn ->
            turn.jsonObject.requiredArray("items").mapNotNull(::conversationMessage)
        }
        return AgentConversation(conversationSummary(thread), messages)
    }

    override suspend fun renameSession(sessionId: SessionId, name: String) {
        val snapshot = name.trim()
        require(snapshot.isNotEmpty()) { "Conversation name must not be blank" }
        request(
            "thread/name/set",
            buildJsonObject {
                put("threadId", sessionId.value)
                put("name", snapshot)
            },
        )
    }

    override suspend fun deleteSession(sessionId: SessionId) {
        request(
            "thread/delete",
            buildJsonObject { put("threadId", sessionId.value) },
        )
        openedSessions -= sessionId
        sessionRuntimeSettings -= sessionId
        synchronized(turnStateLock) {
            activeTurns -= sessionId
            startingTurns -= sessionId
            terminalDuringStart -= sessionId
            cancellingTurns -= sessionId
            cancelledTurns -= sessionId
        }
    }

    override suspend fun openSession(previous: SessionId?, settings: AgentRuntimeSettings): SessionId {
        if (builtInToolDispatcher != null) {
            connection.ensureStarted()
            refreshBuiltInPluginEnablement(settings.workingDirectory ?: "/")
        }
        val params = buildJsonObject {
            previous?.let { put("threadId", it.value) }
            put("approvalPolicy", settings.approvalPreset.approvalPolicy)
            put("approvalsReviewer", settings.approvalPreset.approvalsReviewer)
            put("sandbox", "danger-full-access")
            settings.serviceTier?.let { put("serviceTier", it) }
            settings.workingDirectory?.let { put("cwd", it) }
            if (previous == null) put("ephemeral", false)
            put(
                "developerInstructions",
                "Answer conversationally using Markdown. The shell starts in the user's selected Android " +
                    "workspace and may use ordinary shell commands to inspect and modify files. Use enabled " +
                    "built-in plugin tools for Documents and Telegram; their private backends are not shell commands. Use the " +
                    "built-in web search tool only when the user input contains the structured " +
                    "'${AgentCapability.WEB_SEARCH.promptLabel}' prompt tag.",
            )
            if (previous == null && builtInToolDispatcher != null) {
                put(
                    "dynamicTools",
                    builtInDynamicTools(
                        builtInPluginEnabled.filterValues { it }.keys,
                    ),
                )
            }
            putJsonObject("config") {
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
        }
        val result = request(if (previous == null) "thread/start" else "thread/resume", params).jsonObject
        val sessionId = SessionId(result.requiredObject("thread").requiredString("id"))
        openedSessions += sessionId
        sessionRuntimeSettings[sessionId] = SessionRuntimeSettings(
            workspace = settings.workingDirectory,
            approvalPreset = settings.approvalPreset,
        )
        eventsChannel.send(
            AgentEvent.SessionOpened(
                sessionId = sessionId,
                model = result.optionalString("model"),
                effort = result.optionalString("reasoningEffort"),
                serviceTier = result.optionalString("serviceTier"),
            ),
        )
        return sessionId
    }

    override suspend fun sendTurn(sessionId: SessionId, request: AgentTurnRequest) {
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
            val result = request(
                "turn/start",
                buildJsonObject {
                    put("threadId", sessionId.value)
                    snapshot.clientMessageId?.let { put("clientUserMessageId", it) }
                    put("input", turnInput(snapshot))
                    snapshot.model?.let { put("model", it) }
                    snapshot.effort?.let { put("effort", it) }
                    snapshot.serviceTier?.let { put("serviceTier", it) }
                    snapshot.workingDirectory?.let { put("cwd", it) }
                    put("approvalPolicy", snapshot.approvalPreset.approvalPolicy)
                    put("approvalsReviewer", snapshot.approvalPreset.approvalsReviewer)
                },
            ).jsonObject
            val turnId = result.requiredObject("turn").requiredString("id")
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
            request(
                "thread/shellCommand",
                buildJsonObject {
                    put("threadId", sessionId.value)
                    put("command", snapshot)
                },
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
                request(
                    "turn/interrupt",
                    buildJsonObject {
                        put("threadId", sessionId.value)
                        put("turnId", turnId)
                    },
                )
            } catch (error: RpcException) {
                if (error.code != "-32600" || error.detail != "no active turn to interrupt") throw error
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
        val wireId = pendingApprovalRequests.remove(requestId)
            ?: error("Approval request is no longer pending")
        connection.respond(
            wireId,
            buildJsonObject { put("decision", decision.name.lowercase()) },
        )
    }

    override suspend fun resolveElicitation(
        requestId: String,
        response: AgentElicitationResponse,
    ) {
        val wireId = pendingElicitationRequests.remove(requestId)
            ?: error("Elicitation request is no longer pending")
        connection.respond(wireId, elicitationResponse(response))
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
        connection.close()
        scope.cancel()
        eventsChannel.close()
    }

    private suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMillis: Long? = null,
    ): JsonElement {
        connection.ensureStarted()
        return if (timeoutMillis == null) {
            connection.request(method, params)
        } else {
            connection.request(method, params, timeoutMillis)
        }
    }

    private suspend fun refreshBuiltInPluginEnablement(workingDirectory: String) {
        if (builtInEnablementLoaded.get()) return
        builtInToolGate.withLock {
            if (builtInEnablementLoaded.get()) return
            runCatching {
                val result = connection.request(
                    "plugin/installed",
                    buildJsonObject {
                        put("cwds", buildJsonArray { add(JsonPrimitive(workingDirectory)) })
                    },
                    PLUGIN_CATALOG_TIMEOUT_MILLIS,
                ).jsonObject
                applyBuiltInPluginEnablement(
                    AgentPluginCatalog(parsePluginMarketplaces(result), emptyList()),
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

    private fun handleServerRequest(id: JsonElement, method: String, rawParams: JsonElement?) {
        if (
            method == "item/commandExecution/requestApproval" ||
            method == "item/fileChange/requestApproval"
        ) {
            handleApprovalRequest(id, method, rawParams)
            return
        }
        if (method == "mcpServer/elicitation/request") {
            handleElicitationRequest(id, rawParams)
            return
        }
        if (method == "item/tool/call") {
            handleBuiltInToolCall(id, rawParams)
            return
        }
        rejectServerRequest(id, method)
    }

    private fun handleBuiltInToolCall(id: JsonElement, rawParams: JsonElement?) {
        val pending = runCatching {
            checkNotNull(builtInToolDispatcher) { "Built-in tools are unavailable" }
            val params = rawParams as? JsonObject ?: error("Tool call params are missing")
            check(params["namespace"] == null || params["namespace"] is kotlinx.serialization.json.JsonNull) {
                "Built-in tools do not use namespaces"
            }
            val tool = params.requiredString("tool")
            val pluginId = BUILT_IN_TOOL_PLUGINS[tool] ?: error("Unknown built-in tool")
            val sessionId = SessionId(params.requiredString("threadId"))
            check(sessionId in openedSessions) { "Tool call session is not open" }
            val runtimeSettings = sessionRuntimeSettings[sessionId]
                ?: error("Tool call session settings are unavailable")
            val workspace = runtimeSettings.workspace
                ?: error("A selected Android workspace is required")
            val arguments = params["arguments"] as? JsonObject
                ?: error("Tool arguments must be an object")
            val call = BuiltInToolCall(
                threadId = sessionId.value,
                turnId = params.requiredString("turnId"),
                callId = params.requiredString("callId"),
                pluginId = pluginId,
                tool = tool,
                arguments = arguments,
                workspace = workspace,
                argumentsHash = sha256(canonicalJson(arguments)),
            )
            val startedAt = params["startedAtMs"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it > 0 }
                ?: System.currentTimeMillis()
            PendingBuiltInApproval(
                wireId = id,
                call = call,
                deadlineEpochMillis = startedAt + BUILT_IN_TOOL_DEADLINE_MILLIS,
                requiresPermit = tool in BUILT_IN_MUTATION_TOOLS &&
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
        if (pending.call.tool in BUILT_IN_MUTATION_TOOLS) {
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
                if (pending.requiresPermit) {
                    check(pending.permit.compareAndSet(true, false)) {
                        "Built-in mutation approval is missing or was already used"
                    }
                }
                checkNotNull(builtInToolDispatcher).execute(pending.call)
            }
        }.getOrElse { error -> BuiltInToolResult.text(error.visibleMessage(), false) }
        runCatching { respondBuiltInResult(pending.wireId, result) }
    }

    private fun validateBuiltInCall(pending: PendingBuiltInApproval) {
        val call = pending.call
        check(builtInPluginEnabled[call.pluginId] == true) { "${call.pluginId} is disabled" }
        check(System.currentTimeMillis() <= pending.deadlineEpochMillis) {
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
            buildJsonObject {
                put(
                    "contentItems",
                    buildJsonArray {
                        result.content.forEach { item ->
                            add(
                                when (item) {
                                    is BuiltInToolContent.Text -> buildJsonObject {
                                        put("type", "inputText")
                                        put("text", item.value.take(MAX_BUILT_IN_RESULT_CHARS))
                                    }
                                    is BuiltInToolContent.Image -> buildJsonObject {
                                        check(item.dataUrl.startsWith("data:image/")) {
                                            "Built-in images must use inline data URLs"
                                        }
                                        put("type", "inputImage")
                                        put("imageUrl", item.dataUrl)
                                    }
                                },
                            )
                        }
                    },
                )
                put("success", result.success)
            },
        )
    }

    private fun handleElicitationRequest(id: JsonElement, rawParams: JsonElement?) {
        val elicitation = runCatching {
            val params = rawParams as? JsonObject ?: error("Elicitation params are missing")
            val requestId = id.toString()
            val parsed = parseElicitation(requestId, params)
            check(parsed.sessionId in openedSessions) { "Elicitation session is not open" }
            check(pendingElicitationRequests.putIfAbsent(requestId, id) == null) {
                "Elicitation request ID is already pending"
            }
            parsed
        }.getOrElse {
            runBlocking {
                connection.respond(
                    id,
                    buildJsonObject { put("action", "decline") },
                )
            }
            return
        }
        emitBlocking(AgentEvent.ElicitationRequested(elicitation))
    }

    private fun handleApprovalRequest(
        id: JsonElement,
        method: String,
        rawParams: JsonElement?,
    ) {
        val event = runCatching {
            val params = rawParams as? JsonObject ?: error("Approval params are missing")
            val sessionId = SessionId(params.requiredString("threadId"))
            check(sessionId in openedSessions) { "Approval session is not open" }
            val requestId = id.toString()
            check(pendingApprovalRequests.putIfAbsent(requestId, id) == null) {
                "Approval request ID is already pending"
            }
            val title = if (method.contains("fileChange")) {
                "Approve file changes?"
            } else {
                "Approve command?"
            }
            val details = buildList {
                params.optionalString("reason")?.let(::add)
                params["command"]?.let { add("Command: ${compactDescription(it)}") }
                params.optionalString("cwd")?.let { add("Folder: $it") }
                params["changes"]?.let { add("Changes: ${compactDescription(it)}") }
            }.joinToString("\n").ifBlank { "Codex requested permission to continue." }
            AgentEvent.ApprovalRequested(sessionId, requestId, title, details)
        }.getOrElse {
            respondServerError(id, -32602, "Invalid approval request")
            return
        }
        emitBlocking(event)
    }

    private fun rejectServerRequest(id: JsonElement, method: String) {
        respondServerError(id, -32601, "Client method is not available: $method")
    }

    private fun respondServerError(id: JsonElement, code: Int, message: String) =
        connection.respondError(id, code, message)

    private fun handleNotification(message: JsonObject) {
        val method = message["method"]!!.jsonPrimitive.content
        val params = message["params"] as? JsonObject ?: error("$method params are missing")
        when (method) {
            "account/login/completed" -> {
                val completion = LoginCompletion(
                    loginId = params.requiredString("loginId"),
                    success = params["success"]?.jsonPrimitive?.booleanOrNull == true,
                    error = params["error"]?.jsonPrimitive?.contentOrNull,
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

            "account/updated" -> {
                if (params["authMode"]?.jsonPrimitive?.contentOrNull == "chatgpt") {
                    emitBlockingAuthenticated()
                }
            }

            "skills/changed" -> emitBlocking(AgentEvent.SkillsChanged)

            "app/list/updated" -> emitBlocking(AgentEvent.ConnectorsChanged)

            "mcpServer/oauthLogin/completed" -> emitBlocking(
                AgentEvent.McpOauthCompleted(
                    serverName = params.requiredString("name"),
                    success = params.requiredBoolean("success"),
                    error = params.optionalString("error"),
                ),
            )

            "item/agentMessage/delta" -> {
                val sessionId = SessionId(params.requiredString("threadId"))
                emitBlocking(
                    AgentEvent.TextDelta(
                        sessionId = sessionId,
                        text = params.requiredString("delta"),
                        itemId = params.optionalString("itemId"),
                    ),
                )
            }

            "item/commandExecution/outputDelta" -> {
                val itemId = params.requiredString("itemId")
                if (itemId in userShellItems) {
                    emitBlocking(
                        AgentEvent.ShellOutputDelta(
                            sessionId = SessionId(params.requiredString("threadId")),
                            text = params.requiredString("delta"),
                        ),
                    )
                }
            }

            "item/started" -> updateItemActivity(params, started = true)

            "item/completed" -> {
                completeUserShellItem(params)
                updateItemActivity(params, started = false)
            }

            "turn/completed" -> {
                val sessionId = SessionId(params.requiredString("threadId"))
                val turn = params.requiredObject("turn")
                finishTurn(sessionId, turn.requiredString("id"))
                if (turn.requiredString("status") == "failed") {
                    val detail = (turn["error"] as? JsonObject)
                        ?.get("message")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: "Turn failed"
                    emitBlocking(AgentEvent.Failure(sessionId, "turn_failed", detail, true))
                } else {
                    emitBlocking(AgentEvent.TurnCompleted(sessionId))
                }
            }

            "error" -> {
                if (params["willRetry"]?.jsonPrimitive?.booleanOrNull != true) {
                    val sessionId = params["threadId"]?.jsonPrimitive?.contentOrNull?.let(::SessionId)
                    val detail = (params["error"] as? JsonObject)
                        ?.get("message")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: "Codex turn failed"
                    sessionId?.let {
                        finishTurn(it, params["turnId"]?.jsonPrimitive?.contentOrNull)
                    }
                    emitBlocking(AgentEvent.Failure(sessionId, "turn_error", detail, true))
                }
            }
        }
    }

    private suspend fun emitAuthenticated() {
        if (authenticated.compareAndSet(false, true)) eventsChannel.send(AgentEvent.Authenticated)
    }

    private fun emitBlockingAuthenticated() {
        if (authenticated.compareAndSet(false, true)) emitBlocking(AgentEvent.Authenticated)
    }

    private fun applyLoginCompletion(completion: LoginCompletion) {
        if (completion.success) {
            emitBlockingAuthenticated()
        } else {
            emitBlocking(
                AgentEvent.Failure(
                    null,
                    "authentication_failed",
                    completion.error ?: "Authentication failed",
                    recoverable = true,
                ),
            )
        }
    }

    private fun emitBlocking(event: AgentEvent) {
        runBlocking { eventsChannel.send(event) }
    }

    private fun finishTurn(sessionId: SessionId, turnId: String?) {
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
        if (removedWork) emitBlocking(AgentEvent.WorkActivityChanged(sessionId, null))
    }

    private fun updateItemActivity(params: JsonObject, started: Boolean) {
        val sessionId = params.optionalString("threadId")?.let(::SessionId) ?: return
        val item = params["item"] as? JsonObject ?: return
        val itemId = item.optionalString("id") ?: return
        if (started && item.optionalString("source") == "userShell") {
            userShellItems += itemId
            params.optionalString("turnId")?.let { turnId ->
                synchronized(turnStateLock) { activeTurns[sessionId] = turnId }
            }
        }
        val activity = when (item.optionalString("type")) {
            "commandExecution" -> AgentWorkActivity.RUNNING_COMMAND
            "fileChange" -> AgentWorkActivity.WRITING_FILES
            else -> null
        }
        if (started && activity != null) {
            workItems[itemId] = sessionId to activity
            emitBlocking(AgentEvent.WorkActivityChanged(sessionId, activity))
        } else if (!started && workItems.remove(itemId) != null) {
            emitBlocking(
                AgentEvent.WorkActivityChanged(
                    sessionId,
                    workItems.values.lastOrNull { it.first == sessionId }?.second,
                ),
            )
        }
    }

    private fun completeUserShellItem(params: JsonObject) {
        val sessionId = params.optionalString("threadId")?.let(::SessionId) ?: return
        val item = params["item"] as? JsonObject ?: return
        val itemId = item.optionalString("id") ?: return
        if (userShellItems.remove(itemId)) {
            emitBlocking(
                AgentEvent.ShellCommandCompleted(
                    sessionId = sessionId,
                    exitCode = item["exitCode"]?.jsonPrimitive?.longOrNull?.toInt(),
                ),
            )
        }
    }

    private fun handleConnectionFailure(code: String, message: String) {
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
        emitBlocking(AgentEvent.Failure(null, code, message, recoverable = true))
    }

    private suspend fun <T> requestAllPages(
        method: String,
        baseParams: JsonObject = buildJsonObject {},
        transform: (JsonObject) -> T,
    ): List<T> {
        val values = mutableListOf<T>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = request(
                method,
                buildJsonObject {
                    baseParams.forEach { (name, value) -> put(name, value) }
                    cursor?.let { put("cursor", it) }
                },
            ).jsonObject
            values += page.requiredArray("data").map { transform(it.jsonObject) }
            cursor = page.optionalString("nextCursor")
            check(cursor == null || seenCursors.add(cursor)) { "App-server repeated a pagination cursor" }
        } while (cursor != null)
        return values
    }

    private fun pluginParams(plugin: AgentPluginReference) = buildJsonObject {
        put("pluginName", plugin.name)
        plugin.marketplacePath?.let { put("marketplacePath", it) }
            ?: put("remoteMarketplaceName", plugin.marketplaceName)
    }

    private fun pluginEnablementParams(pluginId: String, enabled: Boolean) = buildJsonObject {
        put("keyPath", "plugins.$pluginId.enabled")
        put("value", enabled)
        put("mergeStrategy", "upsert")
    }

    private fun RpcException.forPlugin(plugin: AgentPluginReference): Throwable =
        if (detail.contains("Plugin not found", ignoreCase = true) ||
            detail.contains("status 404", ignoreCase = true) && detail.contains("/plugins/")) {
            AgentPluginUnavailableException(
                plugin.id,
                plugin.name.replace('-', ' ').replaceFirstChar(Char::uppercase),
            )
        } else {
            this
        }

    private fun elicitationResponse(response: AgentElicitationResponse) = buildJsonObject {
        put("action", response.action.name.lowercase())
        if (response.action == AgentElicitationAction.ACCEPT) {
            putJsonObject("content") {
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
        }
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
        val deadlineEpochMillis: Long,
        val requiresPermit: Boolean,
        val permit: AtomicBoolean = AtomicBoolean(),
    )

    private companion object {
        const val EVENT_BUFFER_SIZE = 64
        const val MAX_PROMPT_CHARS = 100_000
        const val MAX_BUILT_IN_RESULT_CHARS = 250_000
        const val BUILT_IN_TOOL_DEADLINE_MILLIS = 120_000L
        const val SKILL_CHUNK_BYTES = 32 * 1024
        const val PLUGIN_CATALOG_TIMEOUT_MILLIS = 60_000L
        const val CATALOG_CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L
        val EMPTY_PLUGIN_CATALOG_RETRY_DELAYS_MILLIS = longArrayOf(500L, 1_000L, 2_000L)

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
