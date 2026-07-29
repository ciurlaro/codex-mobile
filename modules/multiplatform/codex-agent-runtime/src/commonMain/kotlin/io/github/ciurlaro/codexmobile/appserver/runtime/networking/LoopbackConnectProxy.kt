package io.github.ciurlaro.codexmobile.appserver.runtime

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ASocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.io.encoding.Base64

internal class LoopbackConnectProxy private constructor(
    password: String,
    private val selector: SelectorManager,
    private val server: ServerSocket,
) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val authorization = "Basic " + Base64.Default.encode("codex:$password".encodeToByteArray())
    val url = "http://codex:$password@$LOOPBACK:${(server.localAddress as InetSocketAddress).port}"

    init {
        scope.launch { acceptConnections() }
    }

    private suspend fun acceptConnections() {
        while (job.isActive) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            scope.launch { handle(socket) }
        }
    }

    private suspend fun handle(client: Socket) {
        var upstream: Socket? = null
        var tunnelEstablished = false
        try {
            withTimeout(CONNECT_TIMEOUT) {
                val request = parseRequest(readHeaders(client.openReadChannel()))
                if (request.method != "CONNECT") {
                    respond(client.openWriteChannel(), 405, "Method Not Allowed")
                    return@withTimeout
                }
                if (request.authorization != authorization) {
                    respond(client.openWriteChannel(), 407, "Proxy Authentication Required")
                    return@withTimeout
                }
                val (host, port) = parseAuthority(request.authority) ?: run {
                    respond(client.openWriteChannel(), 403, "Forbidden")
                    return@withTimeout
                }
                val address = runCatching { InetSocketAddress(host, port).resolveAddress() }.getOrNull()
                if (port != 443 || address?.isPublicProxyAddress() != true) {
                    respond(client.openWriteChannel(), 403, "Forbidden")
                    return@withTimeout
                }
                upstream = aSocket(selector).tcp().connect(InetSocketAddress(address, port))
                respond(client.openWriteChannel(), 200, "Connection Established")
                tunnelEstablished = true
            }
            val destination = checkNotNull(upstream)
            coroutineScope {
                val forward = launch { client.openReadChannel().copyTo(destination.openWriteChannel()) }
                val reverse = launch { destination.openReadChannel().copyTo(client.openWriteChannel()) }
                listOf(forward, reverse).joinAll()
            }
        } catch (_: Exception) {
            if (!tunnelEstablished) runCatching {
                respond(client.openWriteChannel(), 502, "Bad Gateway")
            }
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private suspend fun readHeaders(input: ByteReadChannel): String {
        val bytes = ByteArray(MAX_HEADER_BYTES)
        var size = 0
        var matched = 0
        while (size < bytes.size) {
            val byte = input.readByte()
            bytes[size++] = byte
            matched = when {
                byte == HEADER_END[matched] -> matched + 1
                byte == HEADER_END[0] -> 1
                else -> 0
            }
            if (matched == HEADER_END.size) {
                return bytes.decodeToString(0, size, throwOnInvalidSequence = true)
            }
        }
        error("Proxy request headers exceed the byte limit")
    }

    private fun parseRequest(headers: String): ProxyRequest {
        val lines = headers.removeSuffix("\r\n\r\n").split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: emptyList()
        check(requestLine.size == 3 && requestLine[2] in HTTP_VERSIONS) { "Malformed proxy request" }
        val parsedHeaders = lines.drop(1).map { line ->
            check(line.isNotEmpty() && !line.first().isWhitespace() && ':' in line) { "Malformed proxy header" }
            line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
        }
        val authorizations = parsedHeaders.filter { it.first == "proxy-authorization" }.map { it.second }
        return ProxyRequest(requestLine[0], requestLine[1], authorizations.singleOrNull())
    }

    private fun parseAuthority(authority: String): Pair<String, Int>? {
        val separator = authority.lastIndexOf(':')
        if (separator <= 0 || separator == authority.lastIndex) return null
        val rawHost = authority.substring(0, separator)
        val port = authority.substring(separator + 1).toIntOrNull() ?: return null
        val host = when {
            rawHost.startsWith('[') && rawHost.endsWith(']') -> rawHost.substring(1, rawHost.lastIndex)
            ':' !in rawHost -> rawHost
            else -> return null
        }
        if (host.isBlank() || host.any { it.isWhitespace() || it in "/@?#" }) return null
        return host to port
    }

    private suspend fun respond(output: ByteWriteChannel, status: Int, reason: String) {
        output.writeFully("HTTP/1.1 $status $reason\r\n\r\n".encodeToByteArray())
        output.flush()
    }

    override fun close() {
        runCatching { server.close() }
        scope.cancel()
        runCatching { selector.close() }
    }

    private data class ProxyRequest(
        val method: String,
        val authority: String,
        val authorization: String?,
    )

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val MAX_HEADER_BYTES = 16 * 1024
        private val CONNECT_TIMEOUT = 20.seconds
        private val HEADER_END = byteArrayOf(13, 10, 13, 10)
        private val HTTP_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")

        suspend fun start(password: String): LoopbackConnectProxy {
            require(password.isNotBlank() && password.none(Char::isWhitespace))
            val selector = SelectorManager(Dispatchers.Default)
            return try {
                val server = aSocket(selector).tcp().bind(LOOPBACK, 0) { backlogSize = 8 }
                LoopbackConnectProxy(password, selector, server)
            } catch (error: Throwable) {
                selector.close()
                throw error
            }
        }
    }
}

internal fun ByteArray.isPublicProxyAddress(): Boolean = when (size) {
    4 -> isPublicIpv4()
    16 -> when {
        take(10).all { it == 0.toByte() } && this[10] == 0xff.toByte() && this[11] == 0xff.toByte() ->
            copyOfRange(12, 16).isPublicIpv4()
        all { it == 0.toByte() } || take(15).all { it == 0.toByte() } && last() == 1.toByte() -> false
        this[0].toInt() and 0xfe == 0xfc -> false
        this[0] == 0xfe.toByte() && this[1].toInt() and 0xc0 == 0x80 -> false
        this[0] == 0xff.toByte() -> false
        take(4).map { it.toInt() and 0xff } == listOf(0x20, 0x01, 0x0d, 0xb8) -> false
        else -> true
    }
    else -> false
}

private fun ByteArray.isPublicIpv4(): Boolean {
    val octets = map { it.toInt() and 0xff }
    return when {
        octets[0] == 0 || octets[0] == 10 || octets[0] == 127 || octets[0] >= 224 -> false
        octets[0] == 100 && octets[1] in 64..127 -> false
        octets[0] == 169 && octets[1] == 254 -> false
        octets[0] == 172 && octets[1] in 16..31 -> false
        octets[0] == 192 && octets[1] == 168 -> false
        octets[0] == 192 && octets[1] == 0 && octets[2] in setOf(0, 2) -> false
        octets[0] == 198 && octets[1] in 18..19 -> false
        octets[0] == 198 && octets[1] == 51 && octets[2] == 100 -> false
        octets[0] == 203 && octets[1] == 0 && octets[2] == 113 -> false
        else -> true
    }
}
