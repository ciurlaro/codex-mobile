package io.github.ciurlaro.codexmobile.platform.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class ProxyBackedProcess(
    private val process: Process,
    private val proxy: LoopbackConnectProxy,
) : Process() {
    private val proxyClosed = AtomicBoolean()

    override fun getOutputStream(): OutputStream = process.outputStream
    override fun getInputStream(): InputStream = process.inputStream
    override fun getErrorStream(): InputStream = process.errorStream
    override fun waitFor(): Int = try {
        process.waitFor()
    } finally {
        closeProxy()
    }
    override fun exitValue(): Int = process.exitValue().also { closeProxy() }
    override fun destroy() {
        closeProxy()
        process.destroy()
    }

    private fun closeProxy() {
        if (proxyClosed.compareAndSet(false, true)) proxy.close()
    }
}

internal class LoopbackConnectProxy : AutoCloseable {
    private val closed = AtomicBoolean()
    private val server = ServerSocket()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = Executors.newCachedThreadPool()
    private val authorization: String
    val url: String

    init {
        val password = UUID.randomUUID().toString()
        authorization = "Basic " + Base64.getEncoder().encodeToString(
            "codex:$password".toByteArray(StandardCharsets.UTF_8),
        )
        server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 8)
        url = "http://codex:$password@$LOOPBACK:${server.localPort}"
        workers.execute(::acceptConnections)
    }

    private fun acceptConnections() {
        while (!closed.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            sockets += socket
            workers.execute { handle(socket) }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var tunnelEstablished = false
        try {
            client.soTimeout = CONNECT_TIMEOUT_MILLIS
            val lines = readHeaders(client.inputStream).split("\r\n")
            if (lines.firstOrNull()?.startsWith("CONNECT ") != true) {
                respond(client, 405, "Method Not Allowed")
                return
            }
            val suppliedAuthorization = lines.firstOrNull {
                it.startsWith("Proxy-Authorization:", ignoreCase = true)
            }?.substringAfter(':')?.trim()
            if (suppliedAuthorization != authorization) {
                respond(client, 407, "Proxy Authentication Required")
                return
            }

            val authority = lines.first().split(' ').getOrNull(1).orEmpty()
            val destination = runCatching { URI("https://$authority") }.getOrNull()
            val host = destination?.host
            if (host == null || destination.port != 443 || !host.isAllowedCodexHost()) {
                respond(client, 403, "Forbidden")
                return
            }

            upstream = Socket().apply { connect(InetSocketAddress(host, 443), CONNECT_TIMEOUT_MILLIS) }
            sockets += upstream
            client.soTimeout = 0
            respond(client, 200, "Connection Established")
            tunnelEstablished = true
            val reverse = workers.submit {
                try {
                    upstream.inputStream.copyTo(client.outputStream)
                    client.outputStream.flush()
                    runCatching { client.shutdownOutput() }
                } catch (error: Exception) {
                    closePair(client, upstream)
                    throw error
                }
            }
            try {
                client.inputStream.copyTo(upstream.outputStream)
                upstream.outputStream.flush()
                runCatching { upstream.shutdownOutput() }
                reverse.get()
            } finally {
                reverse.cancel(true)
            }
        } catch (_: Exception) {
            if (!tunnelEstablished) runCatching { respond(client, 502, "Bad Gateway") }
        } finally {
            closePair(client, upstream)
        }
    }

    private fun readHeaders(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (bytes.size() < MAX_HEADER_BYTES) {
            val byte = input.read()
            check(byte >= 0) { "Proxy request ended before its headers" }
            bytes.write(byte)
            matched = when {
                byte == HEADER_END[matched].toInt() -> matched + 1
                byte == HEADER_END[0].toInt() -> 1
                else -> 0
            }
            if (matched == HEADER_END.size) return bytes.toString(StandardCharsets.ISO_8859_1.name())
        }
        error("Proxy request headers exceed the byte limit")
    }

    private fun respond(socket: Socket, status: Int, reason: String) {
        socket.outputStream.write("HTTP/1.1 $status $reason\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        socket.outputStream.flush()
    }

    private fun closePair(first: Socket, second: Socket?) {
        sockets -= first
        second?.let { sockets -= it }
        runCatching { first.close() }
        runCatching { second?.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        sockets.toList().forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    private fun String.isAllowedCodexHost(): Boolean {
        val normalized = lowercase()
        return normalized == "openai.com" || normalized.endsWith(".openai.com") ||
            normalized == "chatgpt.com" || normalized.endsWith(".chatgpt.com")
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val MAX_HEADER_BYTES = 16 * 1024
        val HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    }
}
