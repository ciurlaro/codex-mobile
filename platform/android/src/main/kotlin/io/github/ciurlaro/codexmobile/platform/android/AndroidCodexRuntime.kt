package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import io.github.ciurlaro.codexmobile.agent.codex.CodexJsonLine
import io.github.ciurlaro.codexmobile.agent.codex.CodexRuntime
import io.github.ciurlaro.codexmobile.agent.codex.CodexRuntimeEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidCodexRuntime(
    context: Context,
    private val pluginBundle: BuiltInPluginBundle,
    private val runtimeOverride: File? = null,
) : CodexRuntime {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventChannel = Channel<CodexRuntimeEvent>(64)
    private val sendMutex = Mutex()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var proxy: LoopbackConnectProxy? = null

    override val events: Flow<CodexRuntimeEvent> = eventChannel.receiveAsFlow()

    override suspend fun start() {
        check(!closed.get()) { "Codex runtime is closed" }
        check(started.compareAndSet(false, true)) { "Codex runtime was already started" }
        val executable = runtimeOverride
            ?: File(appContext.applicationInfo.nativeLibraryDir, RUNTIME_FILE)
        try {
            check(executable.isFile && executable.canExecute()) {
                "Bundled Codex runtime is missing or not executable"
            }
            val codexHome = File(appContext.noBackupFilesDir, "codex").requireDirectory()
            val home = File(appContext.filesDir, "home").requireDirectory()
            pluginBundle.prepare(home)
            val certificateBundle = prepareCertificateBundle(codexHome)
            val logsDatabase = File(codexHome, LOGS_DATABASE_FILE)
            sanitizeExistingRuntimeLogs(logsDatabase)
            val startedProxy = LoopbackConnectProxy()
            proxy = startedProxy
            val startedProcess = ProcessBuilder(executable.absolutePath)
                .directory(home)
                .redirectErrorStream(false)
                .apply {
                    environment().clear()
                    environment().putAll(minimalEnvironment())
                    environment()["CODEX_HOME"] = codexHome.absolutePath
                    environment()["CODEX_SQLITE_HOME"] = codexHome.absolutePath
                    environment()["HOME"] = home.absolutePath
                    environment()["TMPDIR"] = appContext.cacheDir.absolutePath
                    environment()["SSL_CERT_FILE"] = certificateBundle.absolutePath
                    environment()["HTTPS_PROXY"] = startedProxy.url
                    environment()["https_proxy"] = startedProxy.url
                    environment()["NO_COLOR"] = "1"
                }
                .start()
            process = startedProcess
            awaitRuntimeLogPrivacyGuard(logsDatabase, startedProcess)
            watch(startedProcess)
        } catch (error: Exception) {
            eventChannel.trySend(CodexRuntimeEvent.StartFailure(error.visibleMessage()))
            closeResources()
            throw error
        }
    }

    override suspend fun send(line: CodexJsonLine) = sendMutex.withLock {
        val current = process
        check(current?.isAlive == true) { "Codex app-server is not running" }
        try {
            withContext(Dispatchers.IO) {
                current.outputStream.write(line.value.toByteArray(StandardCharsets.UTF_8))
                current.outputStream.write('\n'.code)
                current.outputStream.flush()
            }
        } catch (error: Exception) {
            eventChannel.trySend(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
            throw error
        }
    }

    private fun watch(current: Process) {
        scope.launch {
            try {
                readStrictJsonLines(current) { line ->
                    eventChannel.send(CodexRuntimeEvent.Received(CodexJsonLine(line)))
                }
                if (!closed.get() && process === current) {
                    eventChannel.send(CodexRuntimeEvent.EndOfFile)
                }
            } catch (error: Exception) {
                if (!closed.get() && process === current) {
                    eventChannel.send(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
                }
            }
        }
        scope.launch {
            runCatching {
                val buffer = ByteArray(8 * 1024)
                while (current.errorStream.read(buffer) >= 0) Unit
            }
        }
        scope.launch {
            val code = runCatching { current.waitFor() }.getOrNull() ?: return@launch
            if (!closed.get() && process === current) {
                eventChannel.send(CodexRuntimeEvent.Exited(code))
            }
        }
    }

    private fun minimalEnvironment(): Map<String, String> = buildMap {
        put("PATH", listOf(System.getenv("PATH").orEmpty(), "/system/bin:/system/xbin")
            .filter(String::isNotBlank).joinToString(":"))
        put("LD_LIBRARY_PATH", appContext.applicationInfo.nativeLibraryDir)
        listOf("LANG", "LC_ALL", "TERM").forEach { name ->
            System.getenv(name)?.takeIf(String::isNotBlank)?.let { put(name, it) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeResources()
        scope.cancel()
        eventChannel.close()
    }

    private fun closeResources() {
        val current = process
        process = null
        runCatching { current?.outputStream?.close() }
        runCatching { current?.inputStream?.close() }
        runCatching { current?.errorStream?.close() }
        if (current?.isAlive == true) current.destroy()
        val exited = runCatching { current?.waitFor(2, TimeUnit.SECONDS) ?: true }.getOrDefault(false)
        if (current?.isAlive == true && !exited) {
            current.destroyForcibly()
            runCatching { current.waitFor(2, TimeUnit.SECONDS) }
        }
        proxy?.close()
        proxy = null
    }

    private fun prepareCertificateBundle(codexHome: File): File = synchronized(CERTIFICATE_LOCK) {
        val certificates = File(SYSTEM_CERTIFICATE_DIRECTORY)
            .listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
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
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            installRuntimeLogPrivacyGuard(database)
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            database.execSQL("VACUUM")
        }
    }

    private fun awaitRuntimeLogPrivacyGuard(databaseFile: File, current: Process) {
        val deadline = SystemClock.elapsedRealtime() + LOG_DATABASE_TIMEOUT_MILLIS
        var lastFailure: SQLiteException? = null
        while (current.isAlive && SystemClock.elapsedRealtime() < deadline) {
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

    private fun File.requireDirectory(): File = apply {
        check(isDirectory || mkdirs()) { "Unable to prepare private runtime directory" }
    }

    private companion object {
        const val RUNTIME_FILE = "libcodex_app_server.so"
        const val LOGS_DATABASE_FILE = "logs_2.sqlite"
        const val LOG_DATABASE_TIMEOUT_MILLIS = 20_000L
        const val LOG_DATABASE_RETRY_MILLIS = 25L
        const val SYSTEM_CERTIFICATE_DIRECTORY = "/system/etc/security/cacerts"
        const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
        val CERTIFICATE_LOCK = Any()
    }
}

private suspend fun readStrictJsonLines(process: Process, onLine: suspend (String) -> Unit) {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = process.inputStream.read(buffer)
        if (count < 0) break
        for (index in 0 until count) {
            val byte = buffer[index]
            if (byte == '\n'.code.toByte()) {
                val value = bytes.toByteArray().let {
                    if (it.lastOrNull() == '\r'.code.toByte()) it.copyOf(it.size - 1) else it
                }
                bytes.reset()
                if (value.isNotEmpty()) onLine(value.decodeStrictUtf8())
            } else {
                check(bytes.size() < 4 * 1024 * 1024) { "JSON-RPC frame exceeds the byte limit" }
                bytes.write(byte.toInt())
            }
        }
    }
    if (bytes.size() > 0) onLine(bytes.toByteArray().decodeStrictUtf8())
}

private fun ByteArray.decodeStrictUtf8(): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this))
    .toString()

private fun Throwable.visibleMessage(): String =
    message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"

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
