package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class BuiltInToolsProtocolTest {
    @Test
    fun `schemas are closed and contain only the stable tool set`() {
        val tools = builtInDynamicTools(setOf(ALPHA_PLUGIN_ID, BETA_PLUGIN_ID), TEST_DEFINITIONS)
        assertEquals(TEST_DEFINITIONS.map { it.name }, tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        tools.forEach { raw ->
            val schema = raw.jsonObject["inputSchema"]!!.jsonObject
            assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
        }
        assertFalse(Regex("\"(command|subcommand|argv|rawArguments)\"").containsMatchIn(tools.toString()))
    }

    @Test
    fun `new chats advertise enabled plugins and stale calls fail immediately after disable`(): Unit = runBlocking {
        var threadStart: JsonObject? = null
        val calls = AtomicInteger()
        val firstResponse = CountDownLatch(1)
        val disabledResponse = CountDownLatch(1)
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> server.respond(message.id, pluginCatalog(alpha = true, beta = false))
                "thread/start" -> {
                    threadStart = message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, thread("thread-1"))
                }
                "turn/start" -> {
                    server.respond(message.id, turn("turn-1"))
                    server.request(900, "item/tool/call", toolCall("alpha_read", "call-1"))
                }
                "config/value/write" -> server.respond(message.id, buildJsonObject {})
                "turn/steer" -> server.respond(message.id, buildJsonObject { put("turnId", "turn-1") })
                null -> when (message.id) {
                    900L -> firstResponse.countDown()
                    901L -> disabledResponse.countDown()
                }
            }
        }
        val client = CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = dispatcher {
                calls.incrementAndGet()
                BuiltInToolResult.text("result")
            },
        )
        try {
            val session = client.openSession(
                settings = AgentRuntimeSettings(workingDirectory = "/workspace"),
            )
            val names = threadStart!!["dynamicTools"]!!.jsonArray.map {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("alpha_read", "alpha_view", "alpha_edit"), names)

            client.sendTurn(session, AgentTurnRequest("read", workingDirectory = "/workspace"))
            assertTrue(firstResponse.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())

            client.setPluginEnabled(ALPHA_PLUGIN_ID, false)
            process.request(901, "item/tool/call", toolCall("alpha_read", "call-2"))
            assertTrue(disabledResponse.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun `failed config writes do not change advertised enablement`(): Unit = runBlocking {
        val advertised = mutableListOf<List<String>>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> server.respond(message.id, pluginCatalog(alpha = true, beta = false))
                "thread/start" -> {
                    advertised += message.objectValue["params"]!!.jsonObject["dynamicTools"]!!.jsonArray.map {
                        it.jsonObject["name"]!!.jsonPrimitive.content
                    }
                    server.respond(message.id, thread("thread-${advertised.size}"))
                }
                "config/value/write" -> server.sendRaw(
                    buildJsonObject {
                        put("id", message.id)
                        putJsonObject("error") { put("code", -32603); put("message", "write failed") }
                    }.toString(),
                )
            }
        }
        CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = dispatcher { BuiltInToolResult.text("unused") },
        ).use { client ->
            client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
            assertFailsWith<RpcException> { client.setPluginEnabled(ALPHA_PLUGIN_ID, false) }
            client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
            assertEquals(advertised[0], advertised[1])
            assertTrue("alpha_edit" in advertised[1])
        }
    }

    @Test
    fun `resumed chat is notified when its original provider has disappeared`(): Unit = runBlocking {
        val states = Files.createTempDirectory("thread-providers-").toFile()
        try {
            val firstRuntime = FakeCodexRuntime { message, server ->
                when (message.method) {
                    "initialize" -> server.respond(message.id, buildJsonObject {})
                    "plugin/installed" -> server.respond(message.id, pluginCatalog(alpha = true, beta = false))
                    "thread/start" -> server.respond(message.id, thread("thread-1"))
                }
            }
            CodexAgentClient(
                runtimeFactory = { firstRuntime },
                requestTimeoutMillis = 1_000,
                threadProviderStateDirectory = states,
                builtInToolDispatcher = dispatcher { BuiltInToolResult.text("unused") },
            ).use { client ->
                client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
            }

            var notice: String? = null
            val resumedRuntime = FakeCodexRuntime { message, server ->
                when (message.method) {
                    "initialize" -> server.respond(message.id, buildJsonObject {})
                    "thread/resume" -> server.respond(message.id, thread("thread-1"))
                    "thread/inject_items" -> {
                        notice = message.objectValue["params"]!!.jsonObject["items"]!!.jsonArray
                            .single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!
                            .jsonPrimitive.content
                        server.respond(message.id, buildJsonObject {})
                    }
                }
            }
            CodexAgentClient(
                runtimeFactory = { resumedRuntime },
                requestTimeoutMillis = 1_000,
                threadProviderStateDirectory = states,
            ).use { client ->
                client.openSession(SessionId("thread-1"), AgentRuntimeSettings(workingDirectory = "/workspace"))
            }

            assertTrue(checkNotNull(notice).contains("$ALPHA_PLUGIN_ID=unavailable"))
        } finally {
            states.deleteRecursively()
        }
    }

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
    fun `manual mutation approval is one use and auto review mutations fail closed`(): Unit = runBlocking {
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
            autoClient.sendTurn(
                session,
                AgentTurnRequest(
                    "send",
                    approvalPreset = AgentApprovalPreset.AUTO_REVIEW,
                    workingDirectory = "/workspace",
                ),
            )
            assertTrue(autoResponse.await(1, TimeUnit.SECONDS))
            assertEquals(0, autoDispatches.get())
        }
    }

    private fun providerMutationRuntime(response: CountDownLatch) = FakeCodexRuntime { message, server ->
        when (message.method) {
            "initialize" -> server.respond(message.id, buildJsonObject {})
            "plugin/installed" -> server.respond(message.id, pluginCatalog(alpha = false, beta = true))
            "thread/start" -> server.respond(message.id, thread("thread-1"))
            "turn/start" -> {
                server.respond(message.id, turn("turn-1"))
                server.request(950, "item/tool/call", toolCall("beta_send", "send-1"))
            }
            null -> if (message.id == 950L) response.countDown()
        }
    }

    private fun pluginCatalog(alpha: Boolean, beta: Boolean) = buildJsonObject {
        putJsonArray("marketplaces") {
            add(buildJsonObject {
                put("name", "codex-mobile")
                putJsonArray("plugins") {
                    add(plugin(ALPHA_PLUGIN_ID, "alpha", alpha))
                    add(plugin(BETA_PLUGIN_ID, "beta", beta))
                }
            })
        }
        putJsonArray("marketplaceLoadErrors") {}
    }

    private fun plugin(id: String, name: String, enabled: Boolean) = buildJsonObject {
        put("id", id)
        put("name", name)
        put("installed", true)
        put("enabled", enabled)
        put("installPolicy", "AVAILABLE")
        put("authPolicy", "ON_USE")
        put("availability", "AVAILABLE")
        putJsonObject("interface") {
            put("displayName", name)
            put("shortDescription", name)
            put("capabilities", buildJsonArray {})
        }
    }

    private fun thread(id: String) = buildJsonObject {
        putJsonObject("thread") { put("id", id) }
    }

    private fun turn(id: String) = buildJsonObject {
        putJsonObject("turn") { put("id", id) }
    }

    private fun toolCall(tool: String, callId: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("callId", callId)
        put("tool", tool)
        put("startedAtMs", System.currentTimeMillis())
        put(
            "arguments",
            if (tool == "alpha_read") {
                buildJsonObject { put("path", "item") }
            } else {
                buildJsonObject { put("to", "me"); put("message", "hello") }
            },
        )
    }
}

private const val ALPHA_PLUGIN_ID = "alpha@fixture"
private const val BETA_PLUGIN_ID = "beta@fixture"

private val TEST_DEFINITIONS = listOf(
    "alpha_read", "alpha_view", "alpha_edit",
).map { tool -> testDefinition(ALPHA_PLUGIN_ID, tool, tool == "alpha_edit") } + listOf(
    "beta_list", "beta_send",
).map { tool -> testDefinition(BETA_PLUGIN_ID, tool, tool == "beta_send") }

private fun testDefinition(pluginId: String, name: String, mutation: Boolean) = BuiltInToolDefinition(
    pluginId,
    name,
    name,
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        put("additionalProperties", false)
    },
    mutation,
)

private fun dispatcher(block: suspend (BuiltInToolCall) -> BuiltInToolResult) = object : BuiltInToolDispatcher {
    override fun definitions() = TEST_DEFINITIONS
    override suspend fun execute(call: BuiltInToolCall) = block(call)
}
