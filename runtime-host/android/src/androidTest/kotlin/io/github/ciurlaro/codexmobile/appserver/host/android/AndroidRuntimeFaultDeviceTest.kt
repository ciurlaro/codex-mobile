package io.github.ciurlaro.codexmobile.appserver.host.android

import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AndroidRuntimeFaultDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun missingRuntimeFailsBeforeLaunch() {
        val missing = File(context.cacheDir, "missing-codex-runtime")

        assertLaunchFails(missing, "missing or not executable")
    }

    @Test
    fun nonExecutableRuntimeFailsBeforeLaunch() {
        val runtime = File.createTempFile("codex-runtime-", ".bin", context.cacheDir)
        try {
            runtime.writeText("not executable")
            assertTrue(runtime.setExecutable(false, false))
            assertFalse(runtime.canExecute())

            assertLaunchFails(runtime, "missing or not executable")
        } finally {
            runtime.delete()
        }
    }

    @Test
    fun corruptWritableRuntimeCannotExecute() {
        val runtime = File.createTempFile("codex-runtime-", ".bin", context.cacheDir)
        try {
            runtime.writeText("not an ELF executable")
            assertTrue(runtime.setExecutable(true, true))

            val failure = runCatching {
                runBlocking { AndroidCodexRuntime(context, runtime).start() }
            }.exceptionOrNull()
            assertTrue("corrupt runtime unexpectedly launched", failure != null)
        } finally {
            runtime.delete()
        }
    }

    @Test
    fun loopbackProxyUsesAndroidDnsForOpenAiTls() {
        LoopbackConnectProxy().use { proxy ->
            val uri = URI(proxy.url)
            val authorization = Base64.getEncoder().encodeToString(
                uri.userInfo.toByteArray(StandardCharsets.UTF_8),
            )
            repeat(5) {
                Socket(uri.host, uri.port).use { socket ->
                    socket.getOutputStream().bufferedWriter(StandardCharsets.US_ASCII).apply {
                        write("CONNECT auth.openai.com:443 HTTP/1.1\r\n")
                        write("Proxy-Authorization: Basic $authorization\r\n\r\n")
                        flush()
                    }

                    assertEquals(
                        "HTTP/1.1 200 Connection Established",
                        socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine(),
                    )
                }
            }
        }
    }

    @Test
    fun runtimeLogPrivacyGuardRemovesAndRejectsRows() {
        val databaseFile = File.createTempFile("codex-logs-", ".sqlite", context.cacheDir)
        try {
            SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
                database.execSQL("CREATE TABLE logs (feedback_log_body TEXT)")
                database.execSQL("INSERT INTO logs VALUES ('before')")

                installRuntimeLogPrivacyGuard(database)
                database.execSQL("INSERT INTO logs VALUES ('after')")

                database.rawQuery("SELECT COUNT(*) FROM logs", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(0, rows.getInt(0))
                }
            }
        } finally {
            databaseFile.delete()
        }
    }

    private fun assertLaunchFails(runtime: File, expectedMessage: String) {
        val failure = runCatching {
            runBlocking { AndroidCodexRuntime(context, runtime).start() }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains(expectedMessage))
    }
}
