package io.github.ciurlaro.codexmobile.app

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.app.composition.AndroidPlatform
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBootstrapDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun missingNonExecutableAndCorruptOverridesFailClosed() {
        val missing = File(context.cacheDir, "missing-codex-runtime").also(File::delete)
        assertStartFails(missing)

        val nonExecutable = File.createTempFile("codex-runtime-", ".bin", context.cacheDir)
        try {
            nonExecutable.writeText("not executable")
            assertTrue(nonExecutable.setExecutable(false, false))
            assertFalse(nonExecutable.canExecute())
            assertStartFails(nonExecutable)
        } finally {
            nonExecutable.delete()
        }

        val corrupt = File.createTempFile("codex-runtime-", ".bin", context.cacheDir)
        try {
            corrupt.writeText("not an ELF executable")
            assertTrue(corrupt.setExecutable(true, true))
            assertStartFails(corrupt)
        } finally {
            corrupt.delete()
        }
    }

    @Test
    fun successfulRuntimeInstallsCertificatePrivacyAndCleanupPolicies(): Unit = runBlocking {
        startAndInitializeRuntime()
        val codexHome = File(context.noBackupFilesDir, "codex")
        assertTrue(File(codexHome, "system-ca.pem").length() > 0)
        assertEventuallyDeleted(File(context.noBackupFilesDir, "codex-app-server.stdout"))

        val databasePath = File(codexHome, "logs_2.sqlite").absolutePath
        AndroidSQLiteDriver().open(databasePath).use { database ->
            database.execSQL("DROP TRIGGER codex_mobile_drop_runtime_logs")
            database.execSQL(
                "INSERT INTO logs (ts, ts_nanos, level, target, feedback_log_body) " +
                    "VALUES (1, 0, 'INFO', 'privacy_test', 'existing sensitive log')",
            )
            assertEquals(1, database.longQuery("SELECT COUNT(*) FROM logs"))
        }

        startAndInitializeRuntime()

        val database = AndroidSQLiteDriver().open(databasePath)
        try {
            assertEquals(0, database.longQuery("SELECT COUNT(*) FROM logs"))
            database.execSQL(
                "INSERT INTO logs (ts, ts_nanos, level, target, feedback_log_body) " +
                    "VALUES (2, 0, 'INFO', 'privacy_test', 'later sensitive log')",
            )
            assertEquals(0, database.longQuery("SELECT COUNT(*) FROM logs"))
            assertEquals(1, database.longQuery("PRAGMA secure_delete"))
            assertEquals(
                1,
                database.longQuery(
                    "SELECT COUNT(*) FROM sqlite_schema WHERE type = 'trigger' " +
                        "AND name = 'codex_mobile_drop_runtime_logs'",
                ),
            )
        } finally {
            database.close()
        }
    }

    private suspend fun startAndInitializeRuntime() = coroutineScope {
        val runtime = AndroidPlatform(context).createCodexRuntime()
        try {
            runtime.start()
            val initialized = async {
                withTimeout(120_000) {
                    runtime.events.first { event ->
                        event is CodexRuntimeEvent.Received && "\"id\":1" in event.line.value
                    }
                }
            }
            runtime.send(
                CodexJsonLine(
                    """{"id":1,"method":"initialize","params":{"clientInfo":{"name":"runtime_policy_test","title":"Runtime Policy Test","version":"1"}}}""",
                ),
            )
            initialized.await()
        } finally {
            runtime.close()
        }
    }

    private fun assertStartFails(executable: File) {
        val failure = runCatching {
            runBlocking {
                AndroidPlatform(context, executable).createCodexRuntime().use { runtime ->
                    runtime.start()
                }
            }
        }.exceptionOrNull()
        assertTrue("Expected runtime startup failure for ${executable.name}", failure != null)
    }

    private fun assertEventuallyDeleted(file: File) {
        repeat(100) {
            if (!file.exists()) return
            Thread.sleep(20)
        }
        assertFalse("${file.name} was not deleted", file.exists())
    }

    private fun androidx.sqlite.SQLiteConnection.longQuery(sql: String): Long = prepare(sql).use { statement ->
        assertTrue(statement.step())
        statement.getLong(0)
    }
}
