package io.github.ciurlaro.codexmobile.agent.codex

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class FakeCodexRuntime(
    private val handler: (ClientMessage, FakeCodexRuntime) -> Unit,
) : CodexRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientMessages = Channel<ClientMessage>(Channel.UNLIMITED)
    private val eventChannel = Channel<CodexRuntimeEvent>(64)
    private val running = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val closedLatch = CountDownLatch(1)

    override val events: Flow<CodexRuntimeEvent> = eventChannel.receiveAsFlow()

    val isAlive: Boolean get() = running.get() && !closed.get()

    override suspend fun start() {
        check(!closed.get()) { "runtime is closed" }
        check(running.compareAndSet(false, true)) { "runtime already started" }
        scope.launch {
            for (message in clientMessages) handler(message, this@FakeCodexRuntime)
        }
    }

    override suspend fun send(line: CodexJsonLine) {
        check(isAlive) { "runtime is not running" }
        val value = kotlinx.serialization.json.Json.parseToJsonElement(line.value).jsonObject
        clientMessages.send(ClientMessage(value))
    }

    fun respond(id: Long?, result: JsonObject) {
        requireNotNull(id)
        sendRaw(buildJsonObject { put("id", id); put("result", result) }.toString())
    }

    fun notify(method: String, params: JsonObject) {
        sendRaw(buildJsonObject { put("method", method); put("params", params) }.toString())
    }

    fun request(id: Long, method: String, params: JsonObject) {
        sendRaw(
            buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString(),
        )
    }

    fun sendStderr(@Suppress("UNUSED_PARAMETER") value: String) = Unit

    fun exit(code: Int) {
        if (running.compareAndSet(true, false)) {
            runBlocking { eventChannel.send(CodexRuntimeEvent.Exited(code)) }
            check(closedLatch.await(1, TimeUnit.SECONDS)) { "client did not close exited runtime" }
        }
    }

    fun closeStdout() {
        if (running.compareAndSet(true, false)) runBlocking {
            eventChannel.send(CodexRuntimeEvent.EndOfFile)
        }
    }

    fun allClientStreamsClosed(): Boolean = closed.get()

    fun sendRaw(value: String) {
        runBlocking { eventChannel.send(CodexRuntimeEvent.Received(CodexJsonLine(value))) }
    }

    override fun close() {
        closed.set(true)
        running.set(false)
        closedLatch.countDown()
        clientMessages.close()
        eventChannel.close()
        scope.cancel()
    }
}

internal data class ClientMessage(val objectValue: JsonObject) {
    val id: Long? = objectValue["id"]?.jsonPrimitive?.content?.toLongOrNull()
    val method: String? = objectValue["method"]?.jsonPrimitive?.content
}
