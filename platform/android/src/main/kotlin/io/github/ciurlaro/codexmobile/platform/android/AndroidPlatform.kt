package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.SystemClock
import io.github.ciurlaro.codexmobile.core.DeviceTool
import io.github.ciurlaro.codexmobile.core.MutationJournal
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import java.io.ByteArrayOutputStream
import java.io.File
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

class AndroidPlatform internal constructor(
    context: Context,
    private val runtimeOverride: File?,
) {
    constructor(context: Context) : this(context, null)

    private val appContext = context.applicationContext

    fun launchProcess(command: List<String>, environment: Map<String, String>): Process {
        require(command == listOf(CODEX_APP_SERVER)) { "Only the bundled Codex app-server may run" }

        val runtime = runtimeOverride ?: File(appContext.applicationInfo.nativeLibraryDir, RUNTIME_FILE)
        check(runtime.isFile && runtime.canExecute()) { "Bundled Codex runtime is missing or not executable" }

        val codexHome = File(appContext.noBackupFilesDir, "codex").requireDirectory()
        val home = File(appContext.filesDir, "home").requireDirectory()
        val certificateBundle = prepareCertificateBundle(codexHome)
        val logsDatabase = File(codexHome, LOGS_DATABASE_FILE)
        sanitizeExistingRuntimeLogs(logsDatabase)
        val proxy = LoopbackConnectProxy()
        var started: Process? = null
        return try {
            val process = ProcessBuilder(runtime.absolutePath)
                .directory(home)
                .redirectErrorStream(false)
                .apply {
                    environment().putAll(environment)
                    environment()["CODEX_HOME"] = codexHome.absolutePath
                    environment()["CODEX_SQLITE_HOME"] = codexHome.absolutePath
                    environment()["HOME"] = home.absolutePath
                    environment()["TMPDIR"] = appContext.cacheDir.absolutePath
                    environment()["SSL_CERT_FILE"] = certificateBundle.absolutePath
                    environment()["HTTPS_PROXY"] = proxy.url
                    environment()["https_proxy"] = proxy.url
                    environment()["NO_COLOR"] = "1"
                }
                .start()
            started = process
            awaitRuntimeLogPrivacyGuard(logsDatabase, process)
            ProxyBackedProcess(process, proxy)
        } catch (error: Exception) {
            started?.destroyForcibly()
            proxy.close()
            throw error
        }
    }

    fun persistScope(treeUri: Uri): ResourceScopeId =
        TODO("Step 02: persist a validated SAF read grant and return an opaque scope ID")

    fun revokeScope(scopeId: ResourceScopeId) {
        TODO("Step 02: release the matching persisted grant and local metadata")
    }

    fun deviceTools(): List<DeviceTool> =
        TODO("Steps 02–03: return only locally registered, scope-validating Android tools")

    fun mutationJournal(): MutationJournal =
        TODO("Step 04: create the durable Android journal only when recovery work begins")

    private fun File.requireDirectory(): File = apply {
        check(isDirectory || mkdirs()) { "Unable to prepare private runtime directory" }
    }

    private fun prepareCertificateBundle(codexHome: File): File = synchronized(CERTIFICATE_LOCK) {
        val certificates = File(SYSTEM_CERTIFICATE_DIRECTORY)
            .listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        check(certificates.isNotEmpty()) { "Android system certificates are unavailable" }

        File(codexHome, "android-system-ca.pem").apply {
            outputStream().buffered().use { output ->
                certificates.forEach { certificate ->
                    certificate.inputStream().use { it.copyTo(output) }
                    output.write('\n'.code)
                }
            }
            check(length() > 0) { "Unable to prepare Android system certificates" }
        }
    }

    private fun sanitizeExistingRuntimeLogs(databaseFile: File) {
        if (!databaseFile.isFile) return
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            installRuntimeLogPrivacyGuard(database)
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            database.execSQL("VACUUM")
        }
    }

    private fun awaitRuntimeLogPrivacyGuard(databaseFile: File, process: Process) {
        val deadline = SystemClock.elapsedRealtime() + LOG_DATABASE_TIMEOUT_MILLIS
        var lastFailure: SQLiteException? = null
        while (process.isAlive && SystemClock.elapsedRealtime() < deadline) {
            if (databaseFile.isFile) {
                try {
                    SQLiteDatabase.openDatabase(
                        databaseFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    ).use(::installRuntimeLogPrivacyGuard)
                    return
                } catch (error: SQLiteException) {
                    lastFailure = error
                }
            }
            SystemClock.sleep(LOG_DATABASE_RETRY_MILLIS)
        }
        throw IllegalStateException("Unable to prepare the private Codex log store", lastFailure)
    }

    private companion object {
        const val CODEX_APP_SERVER = "codex-app-server"
        const val RUNTIME_FILE = "libcodex_app_server.so"
        const val LOGS_DATABASE_FILE = "logs_2.sqlite"
        const val LOG_DATABASE_TIMEOUT_MILLIS = 20_000L
        const val LOG_DATABASE_RETRY_MILLIS = 25L
        const val SYSTEM_CERTIFICATE_DIRECTORY = "/system/etc/security/cacerts"
        val CERTIFICATE_LOCK = Any()
    }
}

internal fun installRuntimeLogPrivacyGuard(database: SQLiteDatabase) {
    database.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    database.beginTransaction()
    try {
        database.delete("logs", null, null)
        database.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS codex_mobile_drop_runtime_logs
            BEFORE INSERT ON logs
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent(),
        )
        database.setTransactionSuccessful()
    } finally {
        database.endTransaction()
    }
}

private class ProxyBackedProcess(
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

            upstream = Socket().apply {
                connect(InetSocketAddress(host, 443), CONNECT_TIMEOUT_MILLIS)
            }
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
            if (matched == HEADER_END.size) {
                return bytes.toString(StandardCharsets.ISO_8859_1.name())
            }
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
