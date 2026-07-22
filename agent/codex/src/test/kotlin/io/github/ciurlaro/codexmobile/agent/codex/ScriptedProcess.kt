package io.github.ciurlaro.codexmobile.agent.codex

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ScriptedProcess(
    handler: (ClientMessage, ScriptedProcess) -> Unit,
) : Process() {
    private val serverInput = PipedInputStream()
    private val clientOutput = PipedOutputStream(serverInput)
    private val clientInput = PipedInputStream()
    private val serverOutput = PipedOutputStream(clientInput)
    private val clientError = PipedInputStream()
    private val serverError = PipedOutputStream(clientError)
    private val alive = AtomicBoolean(true)
    private val exitCode = AtomicInteger()
    private val exited = CountDownLatch(1)
    private val outputClosed = AtomicBoolean(false)
    private val inputClosed = AtomicBoolean(false)
    private val errorClosed = AtomicBoolean(false)
    private val trackedClientOutput = object : FilterOutputStream(clientOutput) {
        override fun close() {
            outputClosed.set(true)
            super.close()
        }
    }
    private val trackedClientInput = object : FilterInputStream(clientInput) {
        override fun close() {
            inputClosed.set(true)
            super.close()
        }
    }
    private val trackedClientError = object : FilterInputStream(clientError) {
        override fun close() {
            errorClosed.set(true)
            super.close()
        }
    }

    init {
        thread(isDaemon = true, name = "fake-app-server") {
            serverInput.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val objectValue = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
                    handler(ClientMessage(objectValue), this)
                }
            }
        }
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

    @Synchronized
    fun sendStderr(value: String) {
        serverError.write("$value\n".toByteArray(StandardCharsets.UTF_8))
        serverError.flush()
    }

    fun exit(code: Int) {
        if (!alive.compareAndSet(true, false)) return
        exitCode.set(code)
        exited.countDown()
    }

    fun closeStdout() {
        serverOutput.close()
    }

    fun allClientStreamsClosed(): Boolean =
        outputClosed.get() && inputClosed.get() && errorClosed.get()

    @Synchronized
    fun sendRaw(value: String) {
        serverOutput.write("$value\n".toByteArray(StandardCharsets.UTF_8))
        serverOutput.flush()
    }

    override fun getOutputStream(): OutputStream = trackedClientOutput

    override fun getInputStream(): InputStream = trackedClientInput

    override fun getErrorStream(): InputStream = trackedClientError

    override fun waitFor(): Int {
        exited.await()
        return exitCode.get()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)

    override fun exitValue(): Int {
        if (alive.get()) throw IllegalThreadStateException("still running")
        return exitCode.get()
    }

    override fun destroy() {
        if (!alive.compareAndSet(true, false)) return
        runCatching { clientOutput.close() }
        runCatching { serverOutput.close() }
        runCatching { serverError.close() }
        runCatching { serverInput.close() }
        runCatching { clientInput.close() }
        runCatching { clientError.close() }
        exited.countDown()
    }

    override fun destroyForcibly(): Process = apply { destroy() }

    override fun isAlive(): Boolean = alive.get()
}

internal data class ClientMessage(val objectValue: JsonObject) {
    val id: Long? = objectValue["id"]?.jsonPrimitive?.content?.toLongOrNull()
    val method: String? = objectValue["method"]?.jsonPrimitive?.content
}
