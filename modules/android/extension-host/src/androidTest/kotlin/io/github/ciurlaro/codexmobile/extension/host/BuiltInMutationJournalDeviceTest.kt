package io.github.ciurlaro.codexmobile.extension.host

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.agent.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.BuiltInToolResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderMutationState
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
        journal.finish(call, ProviderMutationState.INDETERMINATE, result, "before", "after")

        assertEquals(result, journal.find(call)?.result)
        assertThrows(IllegalArgumentException::class.java) {
            journal.find(call.copy(argumentsHash = "hash-b"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            journal.find(call.copy(tool = "sample_upload"))
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
            assertEquals(ProviderMutationState.DISPATCHED, journal.find(call(callId, "hash"))?.state)
        }
    }

    @Test
    fun providerRemovalLeavesOnlyMinimalTerminalTombstones() {
        val pluginId = "compact-${UUID.randomUUID()}@catalog"
        val terminal = call(UUID.randomUUID().toString(), "terminal", pluginId)
        val dispatched = call(UUID.randomUUID().toString(), "dispatched", pluginId)
        val prepared = call(UUID.randomUUID().toString(), "prepared", pluginId)
        val other = call(UUID.randomUUID().toString(), "other", "other-${UUID.randomUUID()}@catalog")
        val otherResult = BuiltInToolResult.text("retained", false)
        BuiltInMutationJournal(context).use { journal ->
            journal.prepare(terminal)
            journal.dispatched(terminal, "before", "after")
            journal.finish(terminal, ProviderMutationState.SUCCEEDED, BuiltInToolResult.text("sensitive"), "before", "after")
            journal.prepare(dispatched)
            journal.dispatched(dispatched, "before", "after")
            journal.prepare(prepared)
            journal.prepare(other)
            journal.finish(other, ProviderMutationState.FAILED, otherResult)

            journal.compact(pluginId)

            val terminalEntry = journal.find(terminal)
            assertEquals(ProviderMutationState.SUCCEEDED, terminalEntry?.state)
            assertNull(terminalEntry?.result)
            assertNull(terminalEntry?.beforeHash)
            assertNull(terminalEntry?.afterHash)
            assertEquals(ProviderMutationState.INDETERMINATE, journal.find(dispatched)?.state)
            assertNull(journal.find(dispatched)?.result)
            assertNull(journal.find(prepared))
            assertEquals(otherResult, journal.find(other)?.result)
        }
    }

    private fun call(
        callId: String,
        hash: String,
        pluginId: String = "sample@catalog",
    ) = BuiltInToolCall(
        threadId = "thread",
        turnId = "turn",
        callId = callId,
        pluginId = pluginId,
        tool = "sample_submit",
        arguments = buildJsonObject { put("message", "hello") },
        workspace = "/storage/emulated/0/Documents",
        argumentsHash = hash,
    )
}
