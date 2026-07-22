package io.github.ciurlaro.codexmobile.platform.android

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolResult
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BuiltInMutationJournalDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exactTerminalResultReplaysAndChangedArgumentsFailClosed() {
        val journal = BuiltInMutationJournal(context)
        val call = call(UUID.randomUUID().toString(), "hash-a")
        val result = BuiltInToolResult.text("exact-result", success = false)

        assertNull(journal.prepare(call))
        journal.dispatched(call, beforeHash = "before", afterHash = "after")
        journal.finish(call, MutationState.INDETERMINATE, result, "before", "after")

        assertEquals(result, journal.find(call)?.result)
        assertThrows(IllegalArgumentException::class.java) {
            journal.find(call.copy(argumentsHash = "hash-b"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            journal.find(call.copy(tool = "telegram_send_file"))
        }
    }

    @Test
    fun preparedAndDispatchedStatesSurviveReopenWithoutImplicitRetry() {
        val callId = UUID.randomUUID().toString()
        BuiltInMutationJournal(context).use { journal ->
            assertNull(journal.prepare(call(callId, "hash")))
            journal.dispatched(call(callId, "hash"))
        }

        BuiltInMutationJournal(context).use { journal ->
            assertEquals(MutationState.DISPATCHED, journal.find(call(callId, "hash"))?.state)
        }
    }

    private fun call(callId: String, hash: String) = BuiltInToolCall(
        threadId = "thread",
        turnId = "turn",
        callId = callId,
        pluginId = "telegram@codex-mobile",
        tool = "telegram_send_text",
        arguments = buildJsonObject { put("message", "hello") },
        workspace = "/storage/emulated/0/Documents",
        argumentsHash = hash,
    )
}
