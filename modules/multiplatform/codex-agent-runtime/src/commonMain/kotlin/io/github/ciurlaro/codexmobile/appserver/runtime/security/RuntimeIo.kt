package io.github.ciurlaro.codexmobile.appserver.runtime

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal const val MAX_RECEIVED_MESSAGE_BYTES = 32 * 1024 * 1024

internal class StrictJsonLineFramer(
    private val maxBytes: Int = MAX_RECEIVED_MESSAGE_BYTES,
) {
    private var pending = ByteArray(8 * 1024)
    private var size = 0

    init {
        require(maxBytes > 0)
    }

    fun accept(bytes: ByteArray, onLine: (String) -> Unit) {
        bytes.forEach { byte ->
            if (byte == '\n'.code.toByte()) {
                emit(onLine)
            } else {
                check(size < maxBytes) { "JSON-RPC frame exceeds $maxBytes bytes" }
                if (size == pending.size) pending = pending.copyOf(minOf(maxBytes, pending.size * 2))
                pending[size++] = byte
            }
        }
    }

    fun finish(onLine: (String) -> Unit) {
        if (size > 0) emit(onLine)
    }

    private fun emit(onLine: (String) -> Unit) {
        val contentSize = if (size > 0 && pending[size - 1] == '\r'.code.toByte()) size - 1 else size
        val line = try {
            pending.decodeToString(0, contentSize, throwOnInvalidSequence = true)
        } catch (error: Exception) {
            throw IllegalArgumentException("JSON-RPC frame is not valid UTF-8", error)
        }
        size = 0
        if (line.isNotEmpty()) onLine(line)
    }
}

internal fun Throwable.visibleMessage(): String =
    message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"

internal fun installRuntimeLogPrivacyGuard(database: SQLiteConnection) {
    database.execSQL("PRAGMA secure_delete=ON")
    database.execSQL("BEGIN IMMEDIATE")
    try {
        database.execSQL("DELETE FROM logs")
        database.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS codex_mobile_drop_runtime_logs
            BEFORE INSERT ON logs
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent(),
        )
        database.execSQL("COMMIT")
    } catch (error: Throwable) {
        runCatching { database.execSQL("ROLLBACK") }
        throw error
    }
}
