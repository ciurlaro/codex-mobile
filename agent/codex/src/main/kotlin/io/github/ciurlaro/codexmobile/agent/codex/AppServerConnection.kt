package io.github.ciurlaro.codexmobile.agent.codex

import java.io.BufferedWriter
import java.io.OutputStreamWriter
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val launchCodexProcess: () -> Process,
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
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMillis: Long = requestTimeoutMillis,
    ): JsonElement {
        ensureStarted()
        return requestOnStarted(method, params, timeoutMillis)
    }

    suspend fun respond(id: JsonElement, result: JsonObject) {
        write(
            buildJsonObject {
                put("id", id)
                put("result", result)
            },
        )
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
        stopProcess(process)
        scope.cancel()
    }

    suspend fun ensureStarted() {
        check(!closed.get()) { "Codex connection is closed" }
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
                )
            }
            terminalReported.set(false)
            val started = try {
                launchCodexProcess()
            } catch (error: Exception) {
                onFailure("process_start", error.visibleMessage())
                throw error
            }
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
            watchProcess(started)
            try {
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
                failProcess(started, "initialize_failed", error.visibleMessage())
                throw error
            }
        }
    }

    private fun watchProcess(started: Process) {
        scope.launch {
            try {
                readUtf8JsonLines(started.inputStream) { line -> handleMessage(started, line) }
                if (!closed.get() && process === started) {
                    failProcess(started, "unexpected_eof", "Codex app-server closed its output")
                }
            } catch (error: Exception) {
                if (!closed.get() && process === started) {
                    failProcess(started, "protocol_failure", error.visibleMessage())
                }
            }
        }
        scope.launch {
            runCatching {
                val buffer = ByteArray(8 * 1024)
                while (started.errorStream.read(buffer) >= 0) {
                    // Drain stderr so it cannot block or corrupt the JSONL channel.
                }
            }
        }
        scope.launch {
            val exitCode = runCatching {
                withContext(Dispatchers.IO) { started.waitFor() }
            }.getOrNull() ?: return@launch
            if (!closed.get() && process === started) {
                failProcess(started, "process_exit", "Codex app-server exited with code $exitCode")
            }
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
            write(
                buildJsonObject {
                    put("id", id)
                    put("method", method)
                    put("params", params)
                },
            )
            return withTimeout(timeoutMillis) { response.await() }
        } finally {
            pendingRequests.remove(id, response)
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
        check(process === started) { "Message arrived from a stale app-server process" }
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
                    detail = error["message"]?.jsonPrimitive?.contentOrNull ?: "App-server request failed",
                ),
            )
        } else {
            response.complete(message["result"] ?: JsonNull)
        }
    }

    private fun failProcess(failed: Process, code: String, message: String) {
        if (process !== failed || !terminalReported.compareAndSet(false, true)) return
        val error = ProcessFailureException(message)
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
        stopProcess(failed)
        onFailure(code, message)
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

    private companion object {
        val JSON = Json { isLenient = false }
        const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
    }
}

internal class RpcException(val code: String, val detail: String) :
    IllegalStateException("App-server error $code: $detail")

private class ProcessFailureException(message: String) : IllegalStateException(message)

private class ConnectionClosedException : IllegalStateException("Codex connection is closed")

internal fun Throwable.visibleMessage(): String =
    message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"
