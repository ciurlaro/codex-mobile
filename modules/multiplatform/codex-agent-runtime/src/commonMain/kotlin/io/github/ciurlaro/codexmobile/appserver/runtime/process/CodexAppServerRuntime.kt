@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import androidx.sqlite.execSQL
import io.github.ciurlaro.codexmobile.appserver.AppServerProtocolIdentity
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.process.Process
import io.matthewnelson.kmp.process.Stdio
import io.matthewnelson.kmp.process.changeDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class CodexAppServerRuntime(
    private val configuration: CodexRuntimeConfiguration,
) : CodexRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventChannel = Channel<CodexRuntimeEvent>(64)
    private val sendMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val logPrivacyGuardInstalled = AtomicBoolean(false)
    private val process = AtomicReference<Process?>(null)
    private val proxy = AtomicReference<LoopbackConnectProxy?>(null)

    override val events: Flow<CodexRuntimeEvent> = eventChannel.receiveAsFlow()

    override suspend fun start() {
        check(!closed.load()) { "Codex runtime is closed" }
        check(started.compareAndSet(false, true)) { "Codex runtime was already started" }
        try {
            prepareDirectories()
            if (configuration.verifyPackagedExecutable) verifyPackagedRuntime()
            check(configuration.executable.isRegularFile()) { "Bundled Codex runtime is missing" }
            val codexHome = Path(configuration.privateDirectory, "codex")
            val certificateBundle = prepareRuntimeCertificateBundle(configuration.certificateSources, codexHome)
            val logsDatabase = Path(codexHome, LOGS_DATABASE_FILE)
            if (sanitizeExistingRuntimeLogs(logsDatabase)) logPrivacyGuardInstalled.store(true)
            val startedProxy = LoopbackConnectProxy.start(configuration.proxyPassword)
            proxy.store(startedProxy)
            val stdoutFile = Path(configuration.privateDirectory, RUNTIME_STDOUT_FILE)
            if (SystemFileSystem.exists(stdoutFile)) SystemFileSystem.delete(stdoutFile)
            val startedProcess = Process.Builder(configuration.executable.toString())
                .changeDir(File(configuration.applicationDirectory.toString()))
                .environment {
                    clear()
                    putAll(
                        buildMinimalRuntimeEnvironment(
                            inherited = configuration.inheritedEnvironment,
                            applicationDirectory = configuration.applicationDirectory,
                            temporaryDirectory = configuration.temporaryDirectory,
                            nativeLibraryDirectory = configuration.nativeLibraryDirectory,
                            codexHome = codexHome,
                            certificateBundle = certificateBundle,
                            proxyUrl = startedProxy.url,
                        ),
                    )
                }
                .stdout(Stdio.File.of(File(stdoutFile.toString())))
                .stderr(Stdio.Null)
                .createProcessAsync()
            process.store(startedProcess)
            val outputJob = attachOutput(startedProcess, stdoutFile)
            awaitRuntimeLogPrivacyGuard(logsDatabase, startedProcess, allowMissingDatabase = true)
            watch(startedProcess, outputJob)
        } catch (error: Exception) {
            eventChannel.trySend(CodexRuntimeEvent.StartFailure(error.visibleMessage()))
            closeResources()
            throw error
        }
    }

    override suspend fun send(line: CodexJsonLine) = sendMutex.withLock {
        val current = checkNotNull(process.load()) { "Codex app-server is not running" }
        check(current.isAlive) { "Codex app-server is not running" }
        val input = checkNotNull(current.input) { "Codex app-server input is unavailable" }
        try {
            input.writeAsync((line.value + '\n').encodeToByteArray())
            input.flushAsync()
            if (!logPrivacyGuardInstalled.load() && process.load() === current) {
                awaitRuntimeLogPrivacyGuard(logsDatabase(), current, allowMissingDatabase = false)
            }
        } catch (error: Exception) {
            eventChannel.trySend(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
            throw error
        }
    }

    private fun attachOutput(current: Process, stdoutFile: Path): Job = scope.launch {
        try {
            consumeProcessOutput(stdoutFile, current::isAlive) { line ->
                eventChannel.trySend(CodexRuntimeEvent.Received(CodexJsonLine(line)))
            }
        } catch (error: Exception) {
            if (!closed.load()) {
                eventChannel.trySend(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
                current.destroy()
            }
        }
    }

    private fun watch(current: Process, outputJob: Job) {
        scope.launch {
            val code = runCatching { current.waitForAsync() }.getOrNull() ?: return@launch
            if (!closed.load() && process.load() === current) {
                outputJob.join()
                eventChannel.send(CodexRuntimeEvent.Exited(code))
                eventChannel.send(CodexRuntimeEvent.EndOfFile)
            }
        }
    }

    private fun prepareDirectories() {
        listOf(
            configuration.applicationDirectory,
            configuration.privateDirectory,
            configuration.temporaryDirectory,
            Path(configuration.privateDirectory, "codex"),
        ).forEach { SystemFileSystem.createDirectories(it) }
    }

    private fun logsDatabase(): Path =
        Path(Path(configuration.privateDirectory, "codex"), LOGS_DATABASE_FILE)

    private fun sanitizeExistingRuntimeLogs(databaseFile: Path): Boolean {
        val database = configuration.sqliteDriver.open(databaseFile.toString())
        try {
            installRuntimeLogPrivacyGuard(database)
            database.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            database.execSQL("VACUUM")
        } finally {
            database.close()
        }
        return true
    }

    private suspend fun awaitRuntimeLogPrivacyGuard(
        databaseFile: Path,
        current: Process,
        allowMissingDatabase: Boolean,
    ) {
        val startedAt = TimeSource.Monotonic.markNow()
        var lastFailure: Throwable? = null
        while (current.isAlive && startedAt.elapsedNow() < LOG_DATABASE_TIMEOUT) {
            if (databaseFile.isRegularFile()) {
                try {
                    val database = configuration.sqliteDriver.open(databaseFile.toString())
                    try {
                        installRuntimeLogPrivacyGuard(database)
                    } finally {
                        database.close()
                    }
                    logPrivacyGuardInstalled.store(true)
                    return
                } catch (error: Throwable) {
                    lastFailure = error
                }
            }
            if (allowMissingDatabase && startedAt.elapsedNow() >= LOG_DATABASE_STARTUP_GRACE) return
            delay(LOG_DATABASE_RETRY)
        }
        throw IllegalStateException("Unable to prepare the private Codex log store", lastFailure)
    }

    private fun verifyPackagedRuntime() {
        val distribution = CodexMobileAppServerRuntime.DISTRIBUTION
        distribution.requireCompatible(
            AppServerProtocolIdentity.APP_SERVER_VERSION,
            AppServerProtocolIdentity.UPSTREAM_REVISION,
            AppServerProtocolIdentity.SCHEMA_SHA256,
            RuntimeEnvironment(
                RuntimeKernel.LINUX,
                RuntimeArchitecture.AARCH64,
                supportsStaticElf = configuration.activeAbi == "arm64-v8a",
            ),
        )
        check(configuration.executable.sha256() == distribution.binarySha256) {
            "Bundled Codex runtime checksum is invalid"
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeResources()
        scope.cancel()
        eventChannel.close()
    }

    private fun closeResources() {
        proxy.exchange(null)?.close()
        process.exchange(null)?.let { current ->
            runCatching { current.input?.close() }
            runCatching { current.close() }
        }
    }

    private companion object {
        const val LOGS_DATABASE_FILE = "logs_2.sqlite"
        const val RUNTIME_STDOUT_FILE = "codex-app-server.stdout"
        val LOG_DATABASE_STARTUP_GRACE = 1.seconds
        val LOG_DATABASE_TIMEOUT = 60.seconds
        val LOG_DATABASE_RETRY = 25.milliseconds
    }
}
