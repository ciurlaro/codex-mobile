package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolDefinition
import io.github.ciurlaro.codexmobile.core.ToolResult
import io.github.ciurlaro.codexmobile.core.deriveConversationTitle
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class CodexAgentClient(
    private val launchProcess: (command: List<String>, environment: Map<String, String>) -> Process,
    private val requestTimeoutMillis: Long = 20_000,
    private val toolDefinitions: List<ToolDefinition> = emptyList(),
) : AgentClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventsChannel = Channel<AgentEvent>(capacity = EVENT_BUFFER_SIZE)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val authMutex = Mutex()
    private val loginStateLock = Any()
    private val cancelledLoginIds = mutableSetOf<String>()
    private val nextRequestId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val toolRequestLock = Any()
    private val pendingToolRequests =
        mutableMapOf<Pair<SessionId, ToolCallId>, ArrayDeque<JsonElement>>()
    private val openedSessions = ConcurrentHashMap.newKeySet<SessionId>()
    private val turnStateLock = Any()
    private val activeTurns = mutableMapOf<SessionId, String>()
    private val startingTurns = mutableSetOf<SessionId>()
    private val terminalDuringStart = mutableMapOf<SessionId, String>()
    private val cancellingTurns = mutableSetOf<SessionId>()
    private val authenticated = AtomicBoolean(false)
    private val terminalReported = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var loginId: String? = null

    private var loginStarting = false
    private var loginCompletedDuringStart: LoginCompletion? = null

    init {
        require(toolDefinitions.map(ToolDefinition::name).distinct().size == toolDefinitions.size) {
            "Dynamic tool names must be unique"
        }
        toolDefinitions.forEach { definition ->
            require(TOOL_NAME.matches(definition.name)) { "Invalid dynamic tool name" }
            require(definition.description.length <= MAX_TOOL_DESCRIPTION_CHARS) {
                "Dynamic tool description is too large"
            }
            check(JSON.parseToJsonElement(definition.inputSchemaJson) is JsonObject) {
                "Dynamic tool schema must be a JSON object"
            }
        }
    }

    override val events: Flow<AgentEvent> = eventsChannel.receiveAsFlow()

    override suspend fun authenticate() = authMutex.withLock {
        ensureStarted()
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
        ensureStarted()
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
        ensureStarted()
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
        synchronized(toolRequestLock) { pendingToolRequests.clear() }
        openedSessions.clear()
    }

    override suspend fun listModels(): List<AgentModel> = requestAllPages("model/list") { item ->
        AgentModel(
            id = item.requiredString("model"),
            displayName = item.requiredString("displayName"),
            description = item.requiredString("description"),
            supportedEfforts = item.requiredArray("supportedReasoningEfforts").map { effort ->
                effort.jsonObject.requiredString("reasoningEffort")
            },
            defaultEffort = item.requiredString("defaultReasoningEffort"),
            isDefault = item.requiredBoolean("isDefault"),
        )
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

    override suspend fun openSession(previous: SessionId?): SessionId {
        val params = buildJsonObject {
            previous?.let { put("threadId", it.value) }
            put("approvalPolicy", "never")
            put("sandbox", "read-only")
            if (previous == null) put("ephemeral", false)
            put(
                "developerInstructions",
                "Answer conversationally in plain text. Use only the registered read-only Android " +
                    "document tools, plus rename_document when the user explicitly asks to rename a " +
                    "disposable document. Android and the user's approval decide whether any change " +
                    "occurs. Treat every tool result as Android's authoritative observation. Use the " +
                    "built-in web search tool only when the user input contains the structured " +
                    "'${AgentCapability.WEB_SEARCH.promptLabel}' prompt tag.",
            )
            if (toolDefinitions.isNotEmpty()) put("dynamicTools", dynamicToolSpecs())
            putJsonObject("config") {
                put("web_search", "live")
                putJsonObject("tools") {
                    putJsonObject("experimental_request_user_input") { put("enabled", false) }
                }
                putJsonObject("features") {
                    put("shell_tool", false)
                    put("code_mode", false)
                    put("multi_agent", false)
                    put("apps", false)
                    put("enable_mcp_apps", false)
                    put("plugins", false)
                    put("image_generation", false)
                    put("goals", false)
                    put("hooks", false)
                    put("skill_mcp_dependency_install", false)
                    put("workspace_dependencies", false)
                    put("standalone_web_search", false)
                }
            }
        }
        val result = request(if (previous == null) "thread/start" else "thread/resume", params).jsonObject
        val sessionId = SessionId(result.requiredObject("thread").requiredString("id"))
        openedSessions += sessionId
        eventsChannel.send(
            AgentEvent.SessionOpened(
                sessionId = sessionId,
                model = result.optionalString("model"),
                effort = result.optionalString("reasoningEffort"),
            ),
        )
        return sessionId
    }

    override suspend fun sendPrompt(sessionId: SessionId, prompt: String) =
        sendTurn(sessionId, AgentTurnRequest(prompt))

    override suspend fun sendTurn(sessionId: SessionId, request: AgentTurnRequest) {
        val snapshot = request.copy(capabilities = request.capabilities.toSet())
        require(snapshot.prompt.isNotBlank() || snapshot.capabilities.isNotEmpty()) {
            "Prompt must not be blank"
        }
        require(snapshot.prompt.length <= MAX_PROMPT_CHARS) { "Prompt is too large" }
        require(snapshot.clientMessageId?.isNotBlank() != false) {
            "Client message ID must not be blank"
        }
        require(snapshot.model?.isNotBlank() != false) { "Model must not be blank" }
        require(snapshot.effort?.isNotBlank() != false) { "Effort must not be blank" }
        synchronized(turnStateLock) {
            check(sessionId !in startingTurns && !activeTurns.containsKey(sessionId)) {
                "A turn is already active for this session"
            }
            startingTurns += sessionId
        }

        try {
            val result = request(
                "turn/start",
                buildJsonObject {
                    put("threadId", sessionId.value)
                    snapshot.clientMessageId?.let { put("clientUserMessageId", it) }
                    put("input", turnInput(snapshot))
                    snapshot.model?.let { put("model", it) }
                    snapshot.effort?.let { put("effort", it) }
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
            synchronized(turnStateLock) {
                startingTurns -= sessionId
                terminalDuringStart.remove(sessionId)
            }
            throw error
        }
    }

    override suspend fun cancelTurn(sessionId: SessionId) {
        val turnId = synchronized(turnStateLock) {
            val active = activeTurns[sessionId] ?: error("No active turn for this session")
            check(cancellingTurns.add(sessionId)) { "Turn cancellation is already in progress" }
            active
        }
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

    override suspend fun submitToolResult(sessionId: SessionId, result: ToolResult) {
        val key = sessionId to result.callId
        val requestId = synchronized(toolRequestLock) {
            pendingToolRequests[key]?.pollFirst()?.also {
                if (pendingToolRequests[key].isNullOrEmpty()) pendingToolRequests.remove(key)
            }
        } ?: error("No pending tool request matches this result")
        val (content, success) = when (result) {
            is ToolResult.Success -> result.outputJson to true
            is ToolResult.Rejected -> buildJsonObject {
                put("status", "rejected")
                put("reason", result.reason)
            }.toString() to false

            is ToolResult.Failed -> buildJsonObject {
                put("status", "failed")
                put("code", result.code)
                put("message", result.message)
            }.toString() to false

            is ToolResult.Unknown -> buildJsonObject {
                put("status", "unknown")
                put("reason", result.reason)
            }.toString() to false
        }
        write(
            buildJsonObject {
                put("id", requestId)
                putJsonObject("result") {
                    put(
                        "contentItems",
                        buildJsonArray {
                            add(buildJsonObject { put("type", "inputText"); put("text", content) })
                        },
                    )
                    put("success", success)
                }
            },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(ClientClosedException()) }
        pending.clear()
        synchronized(toolRequestLock) { pendingToolRequests.clear() }
        openedSessions.clear()
        stopProcess(process)
        eventsChannel.close()
        scope.cancel()
    }

    private suspend fun ensureStarted() {
        check(!closed.get()) { "Codex client is closed" }
        if (process?.isAlive == true) return

        startMutex.withLock {
            if (process?.isAlive == true) return
            process?.let { dead ->
                val exitCode = runCatching { dead.exitValue() }.getOrNull()
                failProcess(
                    dead,
                    "process_exit",
                    exitCode?.let { "Codex app-server exited with code $it" }
                        ?: "Codex app-server stopped unexpectedly",
                    recoverable = true,
                )
            }
            terminalReported.set(false)
            val started = try {
                launchProcess(listOf("codex-app-server"), emptyMap())
            } catch (error: Exception) {
                eventsChannel.send(
                    AgentEvent.Failure(null, "process_start", error.visibleMessage(), recoverable = true),
                )
                throw error
            }
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
            watch(started)

            try {
                requestOnStarted(
                    "initialize",
                    buildJsonObject {
                        putJsonObject("clientInfo") {
                            put("name", "codex_mobile")
                            put("title", "Codex Mobile")
                            put("version", "0.1.0")
                        }
                        if (toolDefinitions.isNotEmpty()) {
                            putJsonObject("capabilities") { put("experimentalApi", true) }
                        }
                    },
                )
                notify("initialized", buildJsonObject {})
            } catch (error: Exception) {
                failProcess(started, "initialize_failed", error.visibleMessage(), recoverable = true)
                throw error
            }
        }
    }

    private fun watch(started: Process) {
        scope.launch {
            try {
                readUtf8JsonLines(started.inputStream) { line -> handleMessage(started, line) }
                if (!closed.get() && process === started) {
                    failProcess(started, "unexpected_eof", "Codex app-server closed its output", true)
                }
            } catch (error: Exception) {
                if (!closed.get() && process === started) {
                    failProcess(started, "protocol_failure", error.visibleMessage(), true)
                }
            }
        }
        scope.launch {
            runCatching {
                val buffer = ByteArray(8 * 1024)
                while (started.errorStream.read(buffer) >= 0) {
                    // Deliberately drain and discard stderr: it must not corrupt JSONL or leak secrets.
                }
            }
        }
        scope.launch {
            val exitCode = runCatching {
                withContext(Dispatchers.IO) { started.waitFor() }
            }.getOrNull() ?: return@launch
            if (!closed.get() && process === started) {
                failProcess(
                    started,
                    "process_exit",
                    "Codex app-server exited with code $exitCode",
                    recoverable = true,
                )
            }
        }
    }

    private suspend fun request(method: String, params: JsonObject): JsonElement {
        ensureStarted()
        return requestOnStarted(method, params)
    }

    private suspend fun requestOnStarted(method: String, params: JsonObject): JsonElement {
        val id = nextRequestId.getAndIncrement()
        val response = CompletableDeferred<JsonElement>()
        pending[id] = response
        try {
            write(
                buildJsonObject {
                    put("id", id)
                    put("method", method)
                    put("params", params)
                },
            )
            return withTimeout(requestTimeoutMillis) { response.await() }
        } finally {
            pending.remove(id, response)
        }
    }

    private suspend fun notify(method: String, params: JsonObject) {
        write(
            buildJsonObject {
                put("method", method)
                put("params", params)
            },
        )
    }

    private suspend fun write(message: JsonObject) = writeMutex.withLock {
        val encoded = message.toString()
        check(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "JSON-RPC message exceeds the byte limit"
        }
        val current = process
        check(current?.isAlive == true) { "Codex app-server is not running" }
        val currentWriter = checkNotNull(writer) { "Codex app-server input is closed" }
        withContext(Dispatchers.IO) {
            currentWriter.write(encoded)
            currentWriter.newLine()
            currentWriter.flush()
        }
    }

    private fun handleMessage(started: Process, line: String) {
        val message = JSON.parseToJsonElement(line) as? JsonObject
            ?: error("App-server message must be a JSON object")
        val method = message["method"]?.jsonPrimitive?.contentOrNull
        val id = message["id"]
        when {
            method != null && id != null -> handleServerRequest(id, method, message["params"])
            method != null -> handleNotification(message)
            id != null -> handleResponse(message)
            else -> error("App-server message has neither method nor id")
        }
        check(process === started) { "Message arrived from a stale app-server process" }
    }

    private fun handleResponse(message: JsonObject) {
        val id = message["id"]?.jsonPrimitive?.longOrNull ?: return
        val response = pending.remove(id) ?: return
        val error = message["error"] as? JsonObject
        if (error != null) {
            response.completeExceptionally(
                RpcException(
                    code = error["code"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                    detail = error["message"]?.jsonPrimitive?.contentOrNull ?: "App-server request failed",
                ),
            )
            return
        }
        response.complete(message["result"] ?: JsonNull)
    }

    private fun handleServerRequest(id: JsonElement, method: String, rawParams: JsonElement?) {
        if (method != "item/tool/call") {
            rejectServerRequest(id, method)
            return
        }
        val request = runCatching {
            val params = rawParams as? JsonObject ?: error("Tool request params are missing")
            val sessionId = SessionId(params.requiredString("threadId"))
            check(sessionId in openedSessions) { "Tool request session is not open" }
            val turnId = params.requiredString("turnId")
            check(
                synchronized(turnStateLock) {
                    sessionId in startingTurns || activeTurns[sessionId] == turnId
                },
            ) { "Tool request turn is not active" }
            val callId = ToolCallId(params.requiredString("callId"))
            val tool = params.requiredString("tool")
            val namespace = params["namespace"]?.jsonPrimitive?.contentOrNull
            val name = if (namespace == null) tool else "$namespace.$tool"
            sessionId to ToolCall(callId, name, (params["arguments"] ?: JsonNull).toString())
        }.getOrElse {
            respondServerError(id, -32602, "Invalid dynamic tool request")
            return
        }
        synchronized(toolRequestLock) {
            pendingToolRequests.getOrPut(request.first to request.second.id) { ArrayDeque() }.add(id)
        }
        emitBlocking(AgentEvent.ToolRequested(request.first, request.second))
    }

    private fun rejectServerRequest(id: JsonElement, method: String) {
        respondServerError(id, -32601, "Client method is not available: $method")
    }

    private fun respondServerError(id: JsonElement, code: Int, message: String) {
        scope.launch {
            runCatching {
                write(
                    buildJsonObject {
                        put("id", id)
                        putJsonObject("error") {
                            put("code", code)
                            put("message", message)
                        }
                    },
                )
            }
        }
    }

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
        }
    }

    private suspend fun failProcess(
        failed: Process,
        code: String,
        message: String,
        recoverable: Boolean,
    ) {
        if (process !== failed || !terminalReported.compareAndSet(false, true)) return
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
        val error = ProcessFailureException(message)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        synchronized(toolRequestLock) { pendingToolRequests.clear() }
        openedSessions.clear()
        stopProcess(failed)
        eventsChannel.send(AgentEvent.Failure(null, code, message, recoverable))
    }

    private fun stopProcess(target: Process?) {
        if (target == null) return
        if (process === target) {
            process = null
            writer = null
        }
        runCatching { target.outputStream.close() }
        runCatching { target.inputStream.close() }
        runCatching { target.errorStream.close() }
        if (target.isAlive) target.destroy()
        val exited = runCatching { target.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        if (target.isAlive && !exited) {
            target.destroyForcibly()
            runCatching { target.waitFor(2, TimeUnit.SECONDS) }
        }
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

    private fun conversationSummary(thread: JsonObject): AgentConversationSummary {
        val preview = cleanTaggedPreview(thread.requiredText("preview"))
        return AgentConversationSummary(
            sessionId = SessionId(thread.requiredString("id")),
            title = deriveConversationTitle(thread.optionalString("name"), preview),
            updatedAtEpochSeconds = thread.requiredLong("updatedAt"),
        )
    }

    private fun conversationMessage(rawItem: JsonElement): AgentMessage? {
        val item = rawItem.jsonObject
        return when (item.requiredString("type")) {
            "userMessage" -> {
                val prompts = item.requiredArray("content").mapNotNull { content ->
                    content.jsonObject.takeIf { it.optionalString("type") == "text" }?.let(::parsePrompt)
                }
                if (prompts.isEmpty()) return null
                AgentMessage(
                    id = item.requiredString("id"),
                    clientId = item.optionalString("clientId"),
                    role = AgentMessageRole.USER,
                    text = prompts.joinToString("\n", transform = ParsedPrompt::text),
                    capabilities = prompts.flatMap(ParsedPrompt::capabilities).toSet(),
                )
            }

            "agentMessage" -> AgentMessage(
                id = item.requiredString("id"),
                clientId = null,
                role = AgentMessageRole.CODEX,
                text = item.requiredText("text"),
            )

            else -> null
        }
    }

    private fun turnInput(request: AgentTurnRequest): JsonArray {
        val capabilities = request.capabilities.sortedBy(AgentCapability::id)
        val tagBlock = capabilities.joinToString("\n", transform = AgentCapability::promptLabel)
        val text = when {
            tagBlock.isEmpty() -> request.prompt
            request.prompt.isBlank() -> tagBlock
            else -> "$tagBlock\n\n${request.prompt}"
        }
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                    if (capabilities.isNotEmpty()) {
                        put(
                            "text_elements",
                            buildJsonArray {
                                var start = 0
                                capabilities.forEach { capability ->
                                    val end = start + capability.promptLabel
                                        .toByteArray(StandardCharsets.UTF_8)
                                        .size
                                    add(
                                        buildJsonObject {
                                            putJsonObject("byteRange") {
                                                put("start", start)
                                                put("end", end)
                                            }
                                            put("placeholder", capability.displayLabel)
                                        },
                                    )
                                    start = end + 1
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun parsePrompt(input: JsonObject): ParsedPrompt {
        val text = input.requiredText("text")
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val capabilities = input.optionalArray("text_elements").mapNotNull { rawElement ->
            runCatching {
                val element = rawElement.jsonObject
                val capability = AgentCapability.entries.singleOrNull {
                    it.displayLabel == element.optionalString("placeholder")
                } ?: return@runCatching null
                val range = element.requiredObject("byteRange")
                val start = range.requiredLong("start").toInt()
                val end = range.requiredLong("end").toInt()
                capability.takeIf {
                    start >= 0 && end in start..bytes.size &&
                        bytes.copyOfRange(start, end).toString(StandardCharsets.UTF_8) == it.promptLabel
                }
            }.getOrNull()
        }.toSet()
        val tagBlock = capabilities.sortedBy(AgentCapability::id)
            .joinToString("\n", transform = AgentCapability::promptLabel)
        val visibleText = when {
            tagBlock.isEmpty() -> text
            text == tagBlock -> ""
            text.startsWith("$tagBlock\n\n") -> text.removePrefix("$tagBlock\n\n")
            else -> text
        }
        return ParsedPrompt(visibleText, capabilities)
    }

    private fun cleanTaggedPreview(preview: String): String {
        val tagBlock = AgentCapability.entries.sortedBy(AgentCapability::id)
            .joinToString("\n", transform = AgentCapability::promptLabel)
        return when {
            preview == tagBlock -> ""
            preview.startsWith("$tagBlock\n\n") -> preview.removePrefix("$tagBlock\n\n")
            else -> preview
        }
    }

    private fun dynamicToolSpecs() = buildJsonArray {
        toolDefinitions.forEach { definition ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put("name", definition.name)
                    put("description", definition.description)
                    put("inputSchema", JSON.parseToJsonElement(definition.inputSchemaJson))
                },
            )
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
            ?: error("Missing $name")

    private fun JsonObject.requiredText(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull ?: error("Missing $name")

    private fun JsonObject.optionalString(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull ?: error("Missing $name")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull ?: error("Missing $name")

    private fun JsonObject.requiredArray(name: String): JsonArray =
        this[name]?.jsonArray ?: error("Missing $name")

    private fun JsonObject.optionalArray(name: String): JsonArray =
        this[name]?.let { if (it is JsonNull) null else it.jsonArray } ?: JsonArray(emptyList())

    private fun JsonObject.requiredObject(name: String): JsonObject =
        this[name] as? JsonObject ?: error("Missing $name")

    private fun Throwable.visibleMessage(): String =
        message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"

    private class RpcException(val code: String, val detail: String) :
        IllegalStateException("App-server error $code: $detail")

    private class ProcessFailureException(message: String) : IllegalStateException(message)

    private class ClientClosedException : IllegalStateException("Codex client is closed")

    private data class LoginCompletion(
        val loginId: String,
        val success: Boolean,
        val error: String?,
    )

    private data class ParsedPrompt(
        val text: String,
        val capabilities: Set<AgentCapability>,
    )

    private companion object {
        val JSON = Json { isLenient = false }
        const val EVENT_BUFFER_SIZE = 64
        const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
        const val MAX_PROMPT_CHARS = 100_000
        const val MAX_TOOL_DESCRIPTION_CHARS = 1_024
        val TOOL_NAME = Regex("^[a-zA-Z0-9_-]{1,128}$")
    }
}

internal fun readUtf8JsonLines(
    input: InputStream,
    maxBytes: Int = 4 * 1024 * 1024,
    onLine: (String) -> Unit,
) {
    require(maxBytes > 0)
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        for (index in 0 until count) {
            val byte = buffer[index]
            if (byte == '\n'.code.toByte()) {
                val line = bytes.toByteArray().dropTrailingCarriageReturn().decodeUtf8()
                bytes.reset()
                if (line.isNotBlank()) onLine(line)
            } else {
                check(bytes.size() < maxBytes) { "JSON-RPC frame exceeds $maxBytes bytes" }
                bytes.write(byte.toInt())
            }
        }
    }
    if (bytes.size() > 0) onLine(bytes.toByteArray().dropTrailingCarriageReturn().decodeUtf8())
}

private fun ByteArray.dropTrailingCarriageReturn(): ByteArray =
    if (lastOrNull() == '\r'.code.toByte()) copyOf(size - 1) else this

private fun ByteArray.decodeUtf8(): String = StandardCharsets.UTF_8
    .newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this))
    .toString()
