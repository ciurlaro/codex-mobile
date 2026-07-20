package io.github.ciurlaro.codexmobile.app

import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.agent.codex.CodexAgentClient
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.ApprovalRequirement
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolEffect
import io.github.ciurlaro.codexmobile.core.ToolExecutor
import io.github.ciurlaro.codexmobile.core.ToolResult
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNotNull
import org.junit.Test

class Step02ReadOnlyIntegrationTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun selectedProviderListsOnlyBoundedMetadata(): Unit = runBlocking {
        val platform = AndroidPlatform(context)
        val scope = platform.currentScopeId()
        assumeNotNull(scope)
        val tool = platform.deviceTools().single { it.name == "list_documents" }
        val result = tool.execute(
            tool.prepare(ToolCall(ToolCallId("provider-smoke"), tool.name, "{}"), scope!!),
        )
        assertTrue(result is ToolResult.Success)
        val output = JSONObject((result as ToolResult.Success).outputJson)
        assertTrue(output.has("entries"))
        assertTrue(output.has("count"))
        assertTrue(result.outputJson.toByteArray().size < 513 * 1_024)
        assertTrue(!result.outputJson.contains("content://"))
    }

    @Test
    fun dynamicToolsReturnOnlyAndroidObservedResults(): Unit = runBlocking {
        requirePhysicalDevice()
        val platform = AndroidPlatform(context)
        val scope = requireNotNull(platform.currentScopeId()) {
            "Select the isolated Step 02 SAF fixture before this physical test"
        }
        val tools = platform.deviceTools()
        val executor = ToolExecutor(tools) { plan ->
            if (plan.effect == ToolEffect.READ) ApprovalRequirement.ALLOW else ApprovalRequirement.DENY
        }
        val observedTools = mutableSetOf<String>()
        var observedEmptyRead = false

        CodexAgentClient(
            launchProcess = platform::launchProcess,
            requestTimeoutMillis = 30_000,
            toolDefinitions = tools.map { it.definition },
        ).use { client ->
            val authenticated = launch {
                withTimeout(30_000) {
                    assertEquals(AgentEvent.Authenticated, client.events.first())
                }
            }
            client.authenticate()
            authenticated.join()
            val session = client.openSession()
            val terminal = CompletableDeferred<Unit>()
            val collector = launch {
                client.events.collect { event ->
                    when (event) {
                        is AgentEvent.ToolRequested -> {
                            assertEquals(session, event.sessionId)
                            val result = executor.execute(executor.prepare(event.call, scope))
                            assertTrue(result is ToolResult.Success)
                            observedTools += event.call.name
                            if (event.call.name == "read_document") {
                                val output = JSONObject((result as ToolResult.Success).outputJson)
                                observedEmptyRead = output.getLong("byteCount") == 0L
                            }
                            client.submitToolResult(session, result)
                        }

                        is AgentEvent.TurnCompleted -> if (event.sessionId == session) {
                            terminal.complete(Unit)
                        }

                        is AgentEvent.Failure -> if (event.sessionId == session) {
                            terminal.completeExceptionally(
                                AssertionError("Dynamic-tool turn failed: ${event.code}"),
                            )
                        }

                        else -> Unit
                    }
                }
            }
            client.sendPrompt(
                session,
                "Use list_documents, then read every listed text document. " +
                    "State only whether the selected folder's document is empty.",
            )
            withTimeout(120_000) { terminal.await() }
            collector.cancelAndJoin()
        }

        assertEquals(setOf("list_documents", "read_document"), observedTools)
        assertTrue(observedEmptyRead)
        assertEquals(scope, AndroidPlatform(context).currentScopeId())
    }

    @Test
    fun scopeSurvivesActivityRecreationAndBackground() {
        assumeNotNull(AndroidPlatform(context).currentScopeId())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(ViewModelProvider(activity)[MainViewModel::class.java].state.value.scopeSelected)
            }
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue(ViewModelProvider(activity)[MainViewModel::class.java].state.value.scopeSelected)
            }
        }
    }

    private fun requirePhysicalDevice() {
        assumeFalse(
            "Live app-server integration requires stock physical-device evidence",
            Build.FINGERPRINT.contains("generic") ||
                Build.FINGERPRINT.contains("emulator") ||
                Build.PRODUCT.contains("sdk") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.MODEL.contains("Emulator"),
        )
    }
}
