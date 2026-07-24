package io.github.ciurlaro.codexmobile.agent.codex

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class AppServerConnection(
    private val runtimeFactory: CodexRuntimeFactory,
    private val clientVersion: String,
    private val requestTimeoutMillis: Long,
    private val onServerRequest: (JsonElement, String, JsonElement?) -> Unit,
    private val onNotification: (JsonObject) -> Unit,
    private val onFailure: (String, String) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val nextRequestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val terminalReported = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var runtime: CodexRuntime? = null

    @Volatile
    private var runtimeEvents: Job? = null

    suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMillis: Long = requestTimeoutMillis,
    ): JsonElement {
        ensureStarted()
        return requestOnStarted(method, params, timeoutMillis)
    }

    suspend fun respond(id: JsonElement, result: JsonObject) {
        write(buildJsonObject { put("id", id); put("result", result) })
    }

    fun respondError(id: JsonElement, code: Int, message: String) {
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingRequests.values.forEach { it.completeExceptionally(ConnectionClosedException()) }
        pendingRequests.clear()
        stopRuntime(runtime)
        scope.cancel()
    }

    suspend fun ensureStarted() {
        check(!closed.get()) { "Codex connection is closed" }
        if (runtime != null) return
        startMutex.withLock {
            if (runtime != null) return
            terminalReported.set(false)
            val started = try {
                runtimeFactory.create()
            } catch (error: Exception) {
                reportStartFailure(error)
                throw error
            }
            runtime = started
            runtimeEvents = scope.launch {
                started.events.collect { event -> handleRuntimeEvent(started, event) }
            }
            try {
                started.start()
                requestOnStarted(
                    "initialize",
                    buildJsonObject {
                        putJsonObject("clientInfo") {
                            put("name", "codex_mobile")
                            put("title", "Codex Mobile")
                            put("version", clientVersion)
                        }
                        putJsonObject("capabilities") {
                            put("experimentalApi", true)
                            put("mcpServerOpenaiFormElicitation", false)
                        }
                    },
                )
                notify("initialized", buildJsonObject {})
            } catch (error: Exception) {
                if (!terminalReported.get()) {
                    failRuntime(started, "initialize_failed", error.visibleMessage())
                }
                throw error
            }
        }
    }

    private fun reportStartFailure(error: Exception) {
        if (terminalReported.compareAndSet(false, true)) {
            onFailure("process_start", error.visibleMessage())
        }
    }

    private fun handleRuntimeEvent(source: CodexRuntime, event: CodexRuntimeEvent) {
        if (runtime !== source || closed.get()) return
        when (event) {
            is CodexRuntimeEvent.Received -> runCatching { handleMessage(source, event.line.value) }
                .onFailure { failRuntime(source, "protocol_failure", it.visibleMessage()) }
            is CodexRuntimeEvent.StartFailure -> failRuntime(source, "process_start", event.message)
            is CodexRuntimeEvent.IoFailure -> failRuntime(source, "io_failure", event.message)
            CodexRuntimeEvent.EndOfFile ->
                failRuntime(source, "unexpected_eof", "Codex app-server closed its output")
            is CodexRuntimeEvent.Exited ->
                failRuntime(source, "process_exit", "Codex app-server exited with code ${event.code}")
        }
    }

    private suspend fun requestOnStarted(
        method: String,
        params: JsonObject,
        timeoutMillis: Long = requestTimeoutMillis,
    ): JsonElement {
        val id = nextRequestId.getAndIncrement()
        val response = CompletableDeferred<JsonElement>()
        pendingRequests[id] = response
        try {
            write(buildJsonObject { put("id", id); put("method", method); put("params", params) })
            return withTimeout(timeoutMillis) { response.await() }
        } finally {
            pendingRequests.remove(id, response)
        }
    }

    private suspend fun notify(method: String, params: JsonObject) {
        write(buildJsonObject { put("method", method); put("params", params) })
    }

    private suspend fun write(message: JsonObject) = writeMutex.withLock {
        val encoded = message.toString()
        check(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "JSON-RPC message exceeds the byte limit"
        }
        checkNotNull(runtime) { "Codex app-server is not running" }.send(CodexJsonLine(encoded))
    }

    private fun handleMessage(source: CodexRuntime, line: String) {
        check(runtime === source) { "Message arrived from a stale app-server runtime" }
        val message = JSON.parseToJsonElement(line) as? JsonObject
            ?: error("App-server message must be a JSON object")
        val method = message["method"]?.jsonPrimitive?.contentOrNull
        val id = message["id"]
        when {
            method != null && id != null -> onServerRequest(id, method, message["params"])
            method != null -> onNotification(message)
            id != null -> handleResponse(message)
            else -> error("App-server message has neither method nor id")
        }
    }

    private fun handleResponse(message: JsonObject) {
        val id = message["id"]?.jsonPrimitive?.longOrNull ?: return
        val response = pendingRequests.remove(id) ?: return
        val error = message["error"] as? JsonObject
        if (error != null) {
            response.completeExceptionally(
                RpcException(
                    code = error["code"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                    detail = error["message"]?.jsonPrimitive?.contentOrNull
                        ?: "App-server request failed",
                ),
            )
        } else {
            response.complete(message["result"] ?: JsonNull)
        }
    }

    private fun failRuntime(failed: CodexRuntime, code: String, message: String) {
        if (runtime !== failed || !terminalReported.compareAndSet(false, true)) return
        val error = RuntimeFailureException(message)
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
        try {
            onFailure(code, message)
        } finally {
            stopRuntime(failed)
        }
    }

    private fun stopRuntime(target: CodexRuntime?) {
        if (target == null) return
        if (runtime === target) runtime = null
        runtimeEvents?.cancel()
        runtimeEvents = null
        runCatching { target.close() }
    }

    private companion object {
        val JSON = Json { isLenient = false }
        const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
    }
}

internal class RpcException(val code: String, val detail: String) :
    IllegalStateException("App-server error $code: $detail")

private class RuntimeFailureException(message: String) : IllegalStateException(message)

private class ConnectionClosedException : IllegalStateException("Codex connection is closed")

internal fun Throwable.visibleMessage(): String =
    message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"
