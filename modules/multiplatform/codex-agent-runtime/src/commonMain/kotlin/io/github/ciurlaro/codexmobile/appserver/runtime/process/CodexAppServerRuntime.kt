@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import io.github.ciurlaro.codexmobile.appserver.AppServerProtocolIdentity
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.process.Process
import io.matthewnelson.kmp.process.ProcessException
import io.matthewnelson.kmp.process.Stdio
import io.matthewnelson.kmp.process.changeDir
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
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

class CodexAppServerRuntime(
    private val configuration: CodexRuntimeConfiguration,
) : CodexRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventChannel = Channel<CodexRuntimeEvent>(64)
    private val sendMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val logPrivacyGuardInstalled = AtomicBoolean(false)
    private val outputFailed = AtomicBoolean(false)
    private val outputEnded = CompletableDeferred<Unit>()
    private val process = AtomicReference<Process?>(null)
    private val proxy = AtomicReference<LoopbackConnectProxy?>(null)

    override val events: Flow<CodexRuntimeEvent> = eventChannel.receiveAsFlow()

    override suspend fun start() {
        check(!closed.load()) { "Codex runtime is closed" }
        check(started.compareAndSet(false, true)) { "Codex runtime was already started" }
        try {
            prepareDirectories()
            configuration.packagedRuntimeEnvironment?.let(::verifyPackagedRuntime)
            check(configuration.executable.isRegularFile()) { "Bundled Codex runtime is missing" }
            val codexHome = Path(configuration.privateDirectory, "codex")
            val certificateBundle = prepareRuntimeCertificateBundle(configuration.certificateSources, codexHome)
            val startedProxy = LoopbackConnectProxy.start(configuration.proxyPassword)
            proxy.store(startedProxy)
            deleteLegacyStdoutSpool()
            val startedProcess = Process.Builder(configuration.executable.toString())
                .changeDir(File(configuration.applicationDirectory.toString()))
                .environment {
                    clear()
                    putAll(
                        buildMinimalRuntimeEnvironment(
                            platform = configuration.platformEnvironment,
                            applicationDirectory = configuration.applicationDirectory,
                            temporaryDirectory = configuration.temporaryDirectory,
                            codexHome = codexHome,
                            certificateBundle = certificateBundle,
                            proxyUrl = startedProxy.url,
                        ),
                    )
                }
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Null)
                .onError(
                    ProcessException.Handler { error ->
                        if (error.context == ProcessException.CTX_FEED_STDOUT) {
                            failOutput(error)
                        } else {
                            throw error
                        }
                    },
                )
                .createProcessAsync()
            process.store(startedProcess)
            startedProcess.stdoutFeed(
                RuntimeProcessOutputFeed(
                    onLine = ::receiveOutput,
                    onFailure = ::failOutput,
                    onEnd = { outputEnded.complete(Unit) },
                ),
            )
            watch(startedProcess)
        } catch (error: Exception) {
            eventChannel.send(CodexRuntimeEvent.StartFailure(error.visibleMessage()))
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
        } catch (error: Exception) {
            eventChannel.send(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
            throw error
        }
    }

    private fun receiveOutput(line: String) {
        installRuntimeLogPrivacyGuard()
        runBlocking {
            eventChannel.send(CodexRuntimeEvent.Received(CodexJsonLine(line)))
        }
    }

    private fun failOutput(error: Throwable) {
        if (closed.load() || !outputFailed.compareAndSet(false, true)) return
        outputEnded.completeExceptionally(error)
        process.load()?.destroy()
        runCatching {
            runBlocking {
                eventChannel.send(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
            }
        }
    }

    private fun watch(current: Process) {
        scope.launch {
            val code = runCatching { current.waitForAsync() }.getOrElse { error ->
                failOutput(error)
                return@launch
            }
            if (!closed.load() && process.load() === current) {
                runCatching { outputEnded.await() }
                    .onFailure(::failOutput)
                if (outputFailed.load()) return@launch
                eventChannel.send(CodexRuntimeEvent.Exited(code))
                eventChannel.send(CodexRuntimeEvent.EndOfFile)
            }
        }
    }

    private fun deleteLegacyStdoutSpool() {
        val legacySpool = Path(configuration.privateDirectory, RUNTIME_STDOUT_FILE)
        if (SystemFileSystem.exists(legacySpool)) SystemFileSystem.delete(legacySpool)
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

    private fun installRuntimeLogPrivacyGuard() {
        if (logPrivacyGuardInstalled.load()) return
        val database = configuration.sqliteDriver.open(logsDatabase().toString())
        try {
            installRuntimeLogPrivacyGuard(database)
        } finally {
            database.close()
        }
        logPrivacyGuardInstalled.store(true)
    }

    private fun verifyPackagedRuntime(environment: RuntimeEnvironment) {
        val distribution = CodexMobileAppServerRuntime.DISTRIBUTION
        distribution.requireCompatible(
            AppServerProtocolIdentity.APP_SERVER_VERSION,
            AppServerProtocolIdentity.UPSTREAM_REVISION,
            AppServerProtocolIdentity.SCHEMA_SHA256,
            environment,
        )
        check(configuration.executable.sha256() == distribution.binarySha256) {
            "Bundled Codex runtime checksum is invalid"
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        eventChannel.close()
        scope.cancel()
        closeResources()
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
    }
}
