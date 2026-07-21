package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentServiceTier
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.deriveConversationTitle
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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
    private val pendingApprovalRequests = ConcurrentHashMap<String, JsonElement>()
    private val workItems = ConcurrentHashMap<String, Pair<SessionId, AgentWorkActivity>>()
    private val userShellItems = ConcurrentHashMap.newKeySet<String>()
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
        userShellItems.clear()
        openedSessions.clear()
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
        synchronized(turnStateLock) {
            activeTurns -= sessionId
            startingTurns -= sessionId
            terminalDuringStart -= sessionId
            cancellingTurns -= sessionId
        }
    }

    override suspend fun openSession(previous: SessionId?, settings: AgentRuntimeSettings): SessionId {
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
                    "workspace and may use ordinary shell commands to inspect and modify files. Native " +
                    "Android commands on PATH include mutool, tesseract, officecli, and tgcli. Use the " +
                    "built-in web search tool only when the user input contains the structured " +
                    "'${AgentCapability.WEB_SEARCH.promptLabel}' prompt tag.",
            )
            putJsonObject("config") {
                put("web_search", "live")
                putJsonObject("tools") {
                    putJsonObject("experimental_request_user_input") { put("enabled", false) }
                }
                putJsonObject("features") {
                    put("shell_tool", true)
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
                serviceTier = result.optionalString("serviceTier"),
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
        require(snapshot.serviceTier?.isNotBlank() != false) { "Service tier must not be blank" }
        require(snapshot.workingDirectory?.startsWith('/') != false) {
            "Working directory must be absolute"
        }
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

    override suspend fun resolveApproval(requestId: String, accept: Boolean) {
        val wireId = pendingApprovalRequests.remove(requestId)
            ?: error("Approval request is no longer pending")
        write(
            buildJsonObject {
                put("id", wireId)
                putJsonObject("result") {
                    put("decision", if (accept) "accept" else "decline")
                }
            },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(ClientClosedException()) }
        pending.clear()
        pendingApprovalRequests.clear()
        workItems.clear()
        userShellItems.clear()
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
                            put("version", "0.2.0-preview.1")
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
        if (
            method == "item/commandExecution/requestApproval" ||
            method == "item/fileChange/requestApproval"
        ) {
            handleApprovalRequest(id, method, rawParams)
            return
        }
        rejectServerRequest(id, method)
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
        }
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

    private fun compactDescription(value: JsonElement): String = value.toString().let {
        if (it.length <= 2_000) it else it.take(2_000) + "…"
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
        pendingApprovalRequests.clear()
        workItems.clear()
        userShellItems.clear()
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
        val labels = AgentCapability.entries.map(AgentCapability::promptLabel).toSet()
        val lines = preview.lines()
        val firstVisible = lines.indexOfFirst { it !in labels && it.isNotEmpty() }
        if (firstVisible <= 0 || lines.take(firstVisible).none { it in labels }) return preview
        return lines.drop(firstVisible).joinToString("\n")
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
