package io.github.ciurlaro.codexmobile.platform.android

import android.system.Os
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.json.JSONObject

data class TelegramStatus(
    val available: Boolean,
    val connected: Boolean,
    val username: String? = null,
)

enum class TelegramAuthPrompt { CODE, PASSWORD }

sealed interface TelegramAuthEvent {
    data class Prompt(val prompt: TelegramAuthPrompt) : TelegramAuthEvent
    data class Connected(val username: String?) : TelegramAuthEvent
    data class Failed(val message: String) : TelegramAuthEvent
}

class TelegramAuthSession internal constructor(
    private val process: Process,
) : Closeable {
    private val reader = BufferedReader(InputStreamReader(process.inputStream))
    private val writer = process.outputStream.bufferedWriter()
    private val output = ArrayDeque<String>()

    @Synchronized
    fun awaitEvent(): TelegramAuthEvent {
        while (true) {
            val line = reader.readLine() ?: break
            output.addLast(line)
            while (output.size > MAX_OUTPUT_LINES) output.removeFirst()
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.optString("type") == "auth_prompt") {
                val prompt = when (json.optString("prompt")) {
                    "password" -> TelegramAuthPrompt.PASSWORD
                    else -> TelegramAuthPrompt.CODE
                }
                return TelegramAuthEvent.Prompt(prompt)
            }
            if (json.optBoolean("authenticated")) {
                return TelegramAuthEvent.Connected(json.optString("username").ifBlank { null })
            }
        }
        val exitCode = process.waitFor()
        return if (exitCode == 0) TelegramAuthEvent.Connected(null)
        else TelegramAuthEvent.Failed(output.lastOrNull().orEmpty().ifBlank { "Telegram login failed" })
    }

    @Synchronized
    fun submitAnswer(value: String) {
        check(process.isAlive) { "Telegram login is no longer active" }
        writer.write(value)
        writer.newLine()
        writer.flush()
    }

    override fun close() {
        runCatching { writer.close() }
        if (process.isAlive) process.destroy()
    }

    private companion object {
        const val MAX_OUTPUT_LINES = 20
    }
}

internal class TelegramCliIntegration(
    private val tools: RuntimeToolBundle,
) {
    val available: Boolean
        get() = BuildConfig.TELEGRAM_API_ID.toIntOrNull()?.let { it > 0 } == true &&
            BuildConfig.TELEGRAM_API_HASH.isNotBlank()

    fun status(): TelegramStatus {
        if (!available) return TelegramStatus(available = false, connected = false)
        val process = tools.startBundledCommand(
            "tgcli",
            listOf("--json", "--timeout", "15s", "auth", "status"),
            emptyMap(),
        )
        val result = process.awaitOutput(20)
        if (result.exitCode != 0) {
            return TelegramStatus(available = true, connected = false)
        }
        val json = result.lines.asReversed().firstNotNullOfOrNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
        return TelegramStatus(
            available = true,
            connected = json?.optBoolean("authenticated") == true,
            username = json?.optString("username")?.ifBlank { null },
        )
    }

    fun startAuthentication(phoneNumber: String): TelegramAuthSession {
        check(available) { "Telegram credentials are not configured in this build" }
        require(PHONE.matches(phoneNumber.trim())) { "Use an international phone number such as +41790000000" }
        val store = tools.telegramStore
        check(store.isDirectory || store.mkdirs()) { "Unable to prepare Telegram storage" }
        val config = JSONObject()
            .put("apiId", BuildConfig.TELEGRAM_API_ID)
            .put("apiHash", BuildConfig.TELEGRAM_API_HASH)
            .put("phoneNumber", phoneNumber.trim())
            .put("mcp", JSONObject().put("enabled", false))
        val configFile = File(store, "config.json")
        configFile.writeText(config.toString(2) + "\n")
        Os.chmod(configFile.absolutePath, 0x180) // 0600
        return TelegramAuthSession(
            tools.startBundledCommand(
                "tgcli",
                listOf("--json", "auth"),
                mapOf("TGCLI_AUTH_BRIDGE" to "1"),
            ),
        )
    }

    fun disconnect(): Boolean {
        if (!tools.telegramStore.exists()) return true
        val process = tools.startBundledCommand(
            "tgcli",
            listOf("--json", "--timeout", "30s", "auth", "logout"),
            emptyMap(),
        )
        if (process.awaitOutput(35).exitCode != 0) return false
        return tools.telegramStore.deleteRecursively()
    }

    private fun Process.awaitOutput(timeoutSeconds: Long): ProcessOutput {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val collector = Thread({
            runCatching {
                inputStream.bufferedReader().useLines { output ->
                    output.forEach { line ->
                        synchronized(lines) {
                            lines += line
                            if (lines.size > MAX_COMMAND_OUTPUT_LINES) lines.removeAt(0)
                        }
                    }
                }
            }
        }, "tgcli-output").apply {
            isDaemon = true
            start()
        }
        val finished = waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            destroyForcibly()
            waitFor(PROCESS_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        collector.join(PROCESS_KILL_TIMEOUT_SECONDS * 1_000L)
        return ProcessOutput(
            exitCode = if (finished) exitValue() else null,
            lines = synchronized(lines) { lines.toList() },
        )
    }

    private companion object {
        val PHONE = Regex("^\\+[1-9][0-9]{6,14}$")
        const val MAX_COMMAND_OUTPUT_LINES = 100
        const val PROCESS_KILL_TIMEOUT_SECONDS = 2L
    }
}

private data class ProcessOutput(
    val exitCode: Int?,
    val lines: List<String>,
)
