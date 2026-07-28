package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal class BuiltInToolsMutationTest : BuiltInToolsProtocolTestBase() {
    @Test
    fun `failed active turn steering is injected after completion and reenable keeps the thread`(): Unit = runBlocking {
        val steered = CountDownLatch(1)
        val firstInjected = CountDownLatch(1)
        val secondInjected = CountDownLatch(1)
        val notices = mutableListOf<String>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> server.respond(message.id, pluginCatalog(alpha = true, beta = false))
                "thread/start" -> server.respond(message.id, thread("thread-1"))
                "turn/start" -> server.respond(message.id, turn("turn-1"))
                "config/value/write" -> server.respond(message.id, buildJsonObject {})
                "turn/steer" -> {
                    steered.countDown()
                    server.sendRaw(buildJsonObject {
                        put("id", message.id)
                        putJsonObject("error") { put("code", -32602); put("message", "turn already completed") }
                    }.toString())
                }
                "thread/inject_items" -> {
                    notices += message.objectValue["params"]!!.jsonObject["items"]!!.jsonArray
                        .single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!
                        .jsonPrimitive.content
                    server.respond(message.id, buildJsonObject {})
                    if (notices.size == 1) firstInjected.countDown() else secondInjected.countDown()
                }
            }
        }
        CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = dispatcher { BuiltInToolResult.text("unused") },
        ).use { client ->
            val session = client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
            client.sendTurn(session, AgentTurnRequest("work", workingDirectory = "/workspace"))
            client.setPluginEnabled(ALPHA_PLUGIN_ID, false)
            assertTrue(steered.await(1, TimeUnit.SECONDS))

            process.notify("turn/completed", buildJsonObject {
                put("threadId", session.value)
                putJsonObject("turn") { put("id", "turn-1"); put("status", "completed") }
            })
            assertTrue(firstInjected.await(1, TimeUnit.SECONDS))
            assertTrue(notices.single().contains("$ALPHA_PLUGIN_ID=unavailable"))

            client.setPluginEnabled(ALPHA_PLUGIN_ID, true)
            assertTrue(secondInjected.await(1, TimeUnit.SECONDS))
            assertTrue(notices.last().contains("$ALPHA_PLUGIN_ID=enabled"))
        }
    }

    @Test
    fun `typed mutations require one manual approval including under auto review`(): Unit = runBlocking {
        val dispatches = AtomicInteger()
        val secondBoundaryRejected = java.util.concurrent.atomic.AtomicBoolean()
        val response = CountDownLatch(1)
        val process = providerMutationRuntime(response)
        val client = CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = object : BuiltInToolDispatcher {
                override fun definitions() = TEST_DEFINITIONS

                override suspend fun execute(call: BuiltInToolCall) = BuiltInToolResult.text("unused", false)

                override suspend fun execute(call: BuiltInToolCall, beforeMutationDispatch: () -> Unit): BuiltInToolResult {
                    beforeMutationDispatch()
                    dispatches.incrementAndGet()
                    secondBoundaryRejected.set(runCatching { beforeMutationDispatch() }.isFailure)
                    return BuiltInToolResult.text("sent")
                }
            },
        )
        try {
            val session = client.openSession(
                settings = AgentRuntimeSettings(AgentApprovalPreset.ASK_ME, workingDirectory = "/workspace"),
            )
            val approval = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.ApprovalRequested>().first() }
            }
            client.sendTurn(
                session,
                AgentTurnRequest(
                    "send",
                    approvalPreset = AgentApprovalPreset.ASK_ME,
                    workingDirectory = "/workspace",
                ),
            )
            val event = approval.await()
            assertEquals(0, dispatches.get())
            client.resolveApproval(event.requestId, AgentApprovalDecision.ACCEPT)
            assertTrue(response.await(1, TimeUnit.SECONDS))
            assertEquals(1, dispatches.get())
            assertTrue(secondBoundaryRejected.get())
            assertFailsWith<IllegalStateException> {
                client.resolveApproval(event.requestId, AgentApprovalDecision.ACCEPT)
            }
        } finally {
            client.close()
        }

        val autoDispatches = AtomicInteger()
        val autoResponse = CountDownLatch(1)
        val autoProcess = providerMutationRuntime(autoResponse)
        CodexAgentClient(
            runtimeFactory = { autoProcess },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = dispatcher {
                autoDispatches.incrementAndGet()
                BuiltInToolResult.text("must not run")
            },
        ).use { autoClient ->
            val session = autoClient.openSession(
                settings = AgentRuntimeSettings(AgentApprovalPreset.AUTO_REVIEW, workingDirectory = "/workspace"),
            )
            val approval = async {
                withTimeout(1_000) { autoClient.events.filterIsInstance<AgentEvent.ApprovalRequested>().first() }
            }
            autoClient.sendTurn(
                session,
                AgentTurnRequest(
                    "send",
                    approvalPreset = AgentApprovalPreset.AUTO_REVIEW,
                    workingDirectory = "/workspace",
                ),
            )
            val event = approval.await()
            assertEquals(0, autoDispatches.get())
            autoClient.resolveApproval(event.requestId, AgentApprovalDecision.ACCEPT)
            assertTrue(autoResponse.await(1, TimeUnit.SECONDS))
            assertEquals(1, autoDispatches.get())
        }
    }

}
