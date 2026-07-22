package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        val tools = builtInDynamicTools(setOf(DOCUMENTS_PLUGIN_ID, TELEGRAM_PLUGIN_ID))
        assertEquals(BUILT_IN_TOOL_PLUGINS.keys.toList(), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
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
                "plugin/installed" -> server.respond(message.id, pluginCatalog(documents = true, telegram = false))
                "thread/start" -> {
                    threadStart = message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, thread("thread-1"))
                }
                "turn/start" -> {
                    server.respond(message.id, turn("turn-1"))
                    server.request(900, "item/tool/call", toolCall("documents_read", "call-1"))
                }
                "config/value/write" -> server.respond(message.id, buildJsonObject {})
                null -> when (message.id) {
                    900L -> firstResponse.countDown()
                    901L -> disabledResponse.countDown()
                }
            }
        }
        val client = CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = BuiltInToolDispatcher {
                calls.incrementAndGet()
                BuiltInToolResult.text("document")
            },
        )
        try {
            val session = client.openSession(
                settings = AgentRuntimeSettings(workingDirectory = "/workspace"),
            )
            val names = threadStart!!["dynamicTools"]!!.jsonArray.map {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("documents_read", "documents_view_pages", "documents_edit"), names)

            client.sendTurn(session, AgentTurnRequest("read", workingDirectory = "/workspace"))
            assertTrue(firstResponse.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())

            client.setPluginEnabled(DOCUMENTS_PLUGIN_ID, false)
            process.request(901, "item/tool/call", toolCall("documents_read", "call-2"))
            assertTrue(disabledResponse.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertFailsWith<IllegalArgumentException> { client.uninstallPlugin(DOCUMENTS_PLUGIN_ID) }
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
                "plugin/installed" -> server.respond(message.id, pluginCatalog(documents = true, telegram = false))
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
            builtInToolDispatcher = BuiltInToolDispatcher { BuiltInToolResult.text("unused") },
        ).use { client ->
            client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
            assertFailsWith<RpcException> { client.setPluginEnabled(DOCUMENTS_PLUGIN_ID, false) }
            client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
            assertEquals(advertised[0], advertised[1])
            assertTrue("documents_edit" in advertised[1])
        }
    }

    @Test
    fun `manual mutation approval is one use and auto review mutations fail closed`(): Unit = runBlocking {
        val dispatches = AtomicInteger()
        val response = CountDownLatch(1)
        val process = telegramMutationRuntime(response)
        val client = CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = BuiltInToolDispatcher {
                dispatches.incrementAndGet()
                BuiltInToolResult.text("sent")
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
            assertFailsWith<IllegalStateException> {
                client.resolveApproval(event.requestId, AgentApprovalDecision.ACCEPT)
            }
        } finally {
            client.close()
        }

        val autoDispatches = AtomicInteger()
        val autoResponse = CountDownLatch(1)
        val autoProcess = telegramMutationRuntime(autoResponse)
        CodexAgentClient(
            runtimeFactory = { autoProcess },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = BuiltInToolDispatcher {
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

    private fun telegramMutationRuntime(response: CountDownLatch) = FakeCodexRuntime { message, server ->
        when (message.method) {
            "initialize" -> server.respond(message.id, buildJsonObject {})
            "plugin/installed" -> server.respond(message.id, pluginCatalog(documents = false, telegram = true))
            "thread/start" -> server.respond(message.id, thread("thread-1"))
            "turn/start" -> {
                server.respond(message.id, turn("turn-1"))
                server.request(950, "item/tool/call", toolCall("telegram_send_text", "send-1"))
            }
            null -> if (message.id == 950L) response.countDown()
        }
    }

    private fun pluginCatalog(documents: Boolean, telegram: Boolean) = buildJsonObject {
        putJsonArray("marketplaces") {
            add(buildJsonObject {
                put("name", "codex-mobile")
                putJsonArray("plugins") {
                    add(plugin("documents@codex-mobile", "documents", documents))
                    add(plugin("telegram@codex-mobile", "telegram", telegram))
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
        put("installPolicy", "INSTALLED_BY_DEFAULT")
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
            if (tool == "documents_read") {
                buildJsonObject { put("path", "a.pdf") }
            } else {
                buildJsonObject { put("to", "me"); put("message", "hello") }
            },
        )
    }
}
