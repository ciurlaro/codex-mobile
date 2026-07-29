package io.github.ciurlaro.codexmobile.appserver.runtime

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeLogPrivacyGuardTest {
    @Test
    fun deletesExistingLogsRejectsLaterInsertsAndEnablesSecureDeletion() {
        BundledSQLiteDriver().open(":memory:").use { database ->
            database.execSQL("CREATE TABLE logs (message TEXT)")
            database.execSQL("INSERT INTO logs VALUES ('existing sensitive log')")

            installRuntimeLogPrivacyGuard(database)

            assertEquals(0, database.longQuery("SELECT COUNT(*) FROM logs"))
            database.execSQL("INSERT INTO logs VALUES ('later sensitive log')")
            assertEquals(0, database.longQuery("SELECT COUNT(*) FROM logs"))
            assertEquals(1, database.longQuery("PRAGMA secure_delete"))
            assertTrue(
                database.longQuery(
                    "SELECT COUNT(*) FROM sqlite_schema WHERE type = 'trigger' " +
                        "AND name = 'codex_mobile_drop_runtime_logs'",
                ) > 0,
            )
        }
    }
}

private fun SQLiteConnection.longQuery(sql: String): Long = prepare(sql).use { statement ->
    check(statement.step())
    statement.getLong(0)
}
