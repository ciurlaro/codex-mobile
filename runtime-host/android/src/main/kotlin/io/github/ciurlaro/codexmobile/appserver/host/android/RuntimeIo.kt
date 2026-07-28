package io.github.ciurlaro.codexmobile.appserver.host.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.SystemClock
import io.github.ciurlaro.codexmobile.appserver.AppServerProtocolIdentity
import io.github.ciurlaro.codexmobile.appserver.host.CodexMobileAppServerRuntime
import io.github.ciurlaro.codexmobile.appserver.host.RuntimeArchitecture
import io.github.ciurlaro.codexmobile.appserver.host.RuntimeEnvironment
import io.github.ciurlaro.codexmobile.appserver.host.RuntimeKernel
import io.github.ciurlaro.codexmobile.appserver.transport.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.transport.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.transport.CodexRuntimeEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

internal const val MAX_RECEIVED_MESSAGE_BYTES = 32 * 1024 * 1024

internal suspend fun readStrictJsonLines(
    input: InputStream,
    maxBytes: Int = MAX_RECEIVED_MESSAGE_BYTES,
    onLine: suspend (String) -> Unit,
) {
    require(maxBytes > 0)
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
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
                check(bytes.size() < maxBytes) { "JSON-RPC frame exceeds $maxBytes bytes" }
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

internal fun Throwable.visibleMessage(): String =
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
