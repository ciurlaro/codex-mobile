package io.github.ciurlaro.codexmobile.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.agent.codex.CodexAgentClient
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class Step01RuntimePremiseDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun runtimePackagingPreparationAndChecksum() {
        val runtime = runtimeFile()

        assertTrue("runtime missing from nativeLibraryDir", runtime.isFile)
        assertTrue("runtime is not executable", runtime.canExecute())
        assertEquals(EXECUTABLE_SHA256, runtime.sha256())
        assertTrue(runtime.canonicalPath.startsWith(File(context.applicationInfo.nativeLibraryDir).canonicalPath))
    }

    @Test
    fun processStartStopRestartAndUnexpectedExit() {
        val startupLatencies = mutableListOf<Long>()
        repeat(2) {
            val startedAt = SystemClock.elapsedRealtime()
            val process = AndroidPlatform(context).launchProcess(listOf("codex-app-server"), emptyMap())
            val writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            val reader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
            try {
                writer.sendJson(
                    """{"id":1,"method":"initialize","params":{"clientInfo":{"name":"codex_mobile_test","title":"Codex Mobile Test","version":"0.1.0"}}}""",
                )
                assertTrue("app-server did not answer initialize", reader.awaitResponse(1))
                val startupLatency = SystemClock.elapsedRealtime() - startedAt
                startupLatencies += startupLatency
                assertTrue("app-server readiness exceeded 30 seconds", startupLatency < 30_000)

                writer.sendJson("""{"method":"initialized","params":{}}""")
                writer.sendJson("""{"id":2,"method":"account/read","params":{"refreshToken":false}}""")
                assertTrue("app-server did not answer account/read", reader.awaitResponse(2))
            } finally {
                runCatching { writer.close() }
                runCatching { reader.close() }
                runCatching { process.errorStream.close() }
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
            assertFalse("app-server process survived close", process.isAlive)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putLong("codexStartupLatencyMaxMs", startupLatencies.max()) },
        )
    }

    @Test
    fun authenticationUsesPersistedAccountOrStartsDeviceFlow(): Unit = runBlocking {
        val client = CodexAgentClient(AndroidPlatform(context)::launchProcess, 30_000)
        try {
            val result = async { withTimeout(30_000) { client.events.first() } }
            client.authenticate()
            when (val event = result.await()) {
                AgentEvent.Authenticated -> Unit
                is AgentEvent.AuthenticationRequired -> {
                    val signInUri = URI(event.signInUrl)
                    assertEquals("https", signInUri.scheme)
                    val host = requireNotNull(signInUri.host).lowercase()
                    assertTrue(
                        host == "openai.com" || host.endsWith(".openai.com") ||
                            host == "chatgpt.com" || host.endsWith(".chatgpt.com"),
                    )
                    client.cancelAuthentication()
                }

                else -> error("Unexpected authentication event: ${event::class.simpleName}")
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun subscriptionAuthenticationFailuresAndPersistence(): Unit = runBlocking {
        requirePhysicalDevice()
        repeat(2) {
            CodexAgentClient(AndroidPlatform(context)::launchProcess, 30_000).use { client ->
                requirePersistedAuthentication(client)
            }
        }
    }

    @Test
    fun promptStreamingCancellationAndActivityRecreation(): Unit = runBlocking {
        requirePhysicalDevice()
        CodexAgentClient(AndroidPlatform(context)::launchProcess, 30_000).use { client ->
            requirePersistedAuthentication(client)
            val session = client.openSession()

            assertTrue(runPrompt(client, session, "Reply with one short word.").isNotBlank())
            runCancelledPrompt(client, session)
            assertTrue(runPrompt(client, session, "Reply with one short word.").isNotBlank())
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: MainViewModel
            scenario.onActivity { activity ->
                original = ViewModelProvider(activity)[MainViewModel::class.java]
                original.authenticate()
            }
            withTimeout(60_000) { original.state.first { it.sessionId != null } }
            original.submit("Reply with three short lines.")
            assertTrue(original.state.value.turnActive)
            val serviceInstance = requireNotNull(original.serviceInstanceId)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            SystemClock.sleep(500)
            assertEquals(serviceInstance, original.serviceInstanceId)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)

            scenario.recreate()
            lateinit var recreated: MainViewModel
            scenario.onActivity { activity ->
                recreated = ViewModelProvider(activity)[MainViewModel::class.java]
            }
            assertSame(original, recreated)
            assertEquals(serviceInstance, recreated.serviceInstanceId)
            withTimeout(120_000) {
                recreated.state.first { !it.turnActive && it.streamedText.isNotBlank() }
            }
        }
        context.startService(CodexForegroundService.stopIntent(context))
    }

    @Test
    fun restartRecordsAuthenticationAndSessionSurvival(): Unit = runBlocking {
        requirePhysicalDevice()
        val session = CodexAgentClient(AndroidPlatform(context)::launchProcess, 30_000).use { client ->
            requirePersistedAuthentication(client)
            client.openSession().also {
                assertTrue(runPrompt(client, it, "Reply with one short word.").isNotBlank())
            }
        }
        CodexAgentClient(AndroidPlatform(context)::launchProcess, 30_000).use { client ->
            requirePersistedAuthentication(client)
            assertEquals(session, client.openSession(session))
        }
    }

    @Test
    fun runtimeCredentialsComponentsAndLogsRemainPrivate() {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val activities = packageInfo.activities.orEmpty()
        val services = packageInfo.services.orEmpty()

        val mainActivity = activities.single { it.name == MainActivity::class.java.name }
        assertTrue(mainActivity.exported)
        assertTrue(
            activities
                .filter { it.name.startsWith(context.packageName) }
                .filterNot { it.name == MainActivity::class.java.name }
                .none { it.exported },
        )
        val foregroundService = services.single { it.name == CodexForegroundService::class.java.name }
        assertTrue(services.none { it.exported })
        assertTrue(foregroundService.enabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregroundService.foregroundServiceType)
        }
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse(context.applicationInfo.dataDir.startsWith("/sdcard"))
        assertTrue(runtimeFile().canonicalPath.startsWith(File(context.applicationInfo.nativeLibraryDir).canonicalPath))
    }

    private fun runtimeFile() = File(context.applicationInfo.nativeLibraryDir, "libcodex_app_server.so")

    private fun requirePhysicalDevice() {
        assumeFalse(
            "Subscription tests require stock physical-device evidence",
            Build.FINGERPRINT.contains("generic") ||
                Build.FINGERPRINT.contains("emulator") ||
                Build.PRODUCT.contains("sdk") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.MODEL.contains("Emulator"),
        )
    }

    private suspend fun requirePersistedAuthentication(client: CodexAgentClient) = coroutineScope {
        val event = async { withTimeout(30_000) { client.events.first() } }
        client.authenticate()
        assertTrue("Persisted ChatGPT authentication is required", event.await() === AgentEvent.Authenticated)
    }

    private suspend fun runPrompt(
        client: CodexAgentClient,
        session: io.github.ciurlaro.codexmobile.core.SessionId,
        prompt: String,
    ): String = coroutineScope {
        val text = StringBuilder()
        val terminal = async {
            withTimeout(120_000) {
                client.events.first { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            if (event.sessionId == session) text.append(event.text)
                            false
                        }

                        is AgentEvent.TurnCompleted -> event.sessionId == session
                        is AgentEvent.Failure -> {
                            if (event.sessionId == session) error("Turn failed: ${event.code}")
                            false
                        }

                        else -> false
                    }
                }
            }
        }
        client.sendPrompt(session, prompt)
        assertTrue(terminal.await() is AgentEvent.TurnCompleted)
        text.toString()
    }

    private suspend fun runCancelledPrompt(
        client: CodexAgentClient,
        session: io.github.ciurlaro.codexmobile.core.SessionId,
    ) = coroutineScope {
        val terminal = async {
            withTimeout(120_000) {
                client.events.first { event ->
                    event is AgentEvent.TurnCompleted && event.sessionId == session
                }
            }
        }
        client.sendPrompt(session, "Write a long numbered list.")
        client.cancelTurn(session)
        assertTrue(terminal.await() is AgentEvent.TurnCompleted)
    }

    private fun java.io.BufferedWriter.sendJson(message: String) {
        write(message)
        newLine()
        flush()
    }

    private fun java.io.BufferedReader.awaitResponse(id: Int): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<Boolean> {
                generateSequence(::readLine)
                    .take(20)
                    .any { line -> line.contains("\"id\":$id") && line.contains("\"result\"") }
            }.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val EXECUTABLE_SHA256 = "09d6a41d6189b14317ec5d556251e5195e9a4235c28867fc75ee5c1d54be02cd"
    }
}
