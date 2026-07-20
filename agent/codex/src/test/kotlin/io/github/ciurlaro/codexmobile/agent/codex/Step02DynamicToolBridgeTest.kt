package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.ToolDefinition
import io.github.ciurlaro.codexmobile.core.ToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class Step02DynamicToolBridgeTest {
    @Test
    fun `registers dynamic tools and correlates duplicate call IDs to distinct responses`(): Unit =
        runBlocking {
            var initializeParams: JsonObject? = null
            var threadParams: JsonObject? = null
            val responses = mutableMapOf<Long, JsonObject>()
            var malformedCode: Int? = null
            var foreignSessionCode: Int? = null
            val responseLatch = CountDownLatch(4)
            val process = ScriptedProcess { message, server ->
                when (message.method) {
                    "initialize" -> {
                        initializeParams = message.objectValue["params"]!!.jsonObject
                        server.respond(message.id, buildJsonObject {})
                    }

                    "thread/start" -> {
                        threadParams = message.objectValue["params"]!!.jsonObject
                        server.respond(
                            message.id,
                            buildJsonObject { putJsonObject("thread") { put("id", "thread") } },
                        )
                    }

                    "turn/start" -> {
                        server.respond(
                            message.id,
                            buildJsonObject { putJsonObject("turn") { put("id", "turn") } },
                        )
                        repeat(2) { index ->
                            server.request(
                                60L + index,
                                "item/tool/call",
                                buildJsonObject {
                                    put("threadId", "thread")
                                    put("turnId", "turn")
                                    put("callId", "duplicate")
                                    put("tool", "read_document")
                                    putJsonObject("arguments") { put("documentId", "opaque") }
                                },
                            )
                        }
                        server.request(
                            62L,
                            "item/tool/call",
                            buildJsonObject {
                                put("threadId", "thread")
                                put("tool", "read_document")
                            },
                        )
                        server.request(
                            63L,
                            "item/tool/call",
                            buildJsonObject {
                                put("threadId", "foreign-thread")
                                put("turnId", "turn")
                                put("callId", "foreign-call")
                                put("tool", "read_document")
                                putJsonObject("arguments") { put("documentId", "opaque") }
                            },
                        )
                    }

                    null -> message.id?.let { id ->
                        if (id == 60L || id == 61L) {
                            synchronized(responses) {
                                responses[id] = message.objectValue["result"]!!.jsonObject
                            }
                            responseLatch.countDown()
                        } else if (id == 62L) {
                            malformedCode = message.objectValue["error"]!!.jsonObject["code"]!!
                                .jsonPrimitive.content.toInt()
                            responseLatch.countDown()
                        } else if (id == 63L) {
                            foreignSessionCode = message.objectValue["error"]!!.jsonObject["code"]!!
                                .jsonPrimitive.content.toInt()
                            responseLatch.countDown()
                        }
                    }
                }
            }
            val definition = ToolDefinition(
                name = "read_document",
                description = "Read a bounded document",
                inputSchemaJson = "{\"type\":\"object\",\"additionalProperties\":false}",
            )
            val client = CodexAgentClient(
                { _, _ -> process },
                requestTimeoutMillis = 1_000,
                toolDefinitions = listOf(definition),
            )
            try {
                val session = client.openSession()
                val calls = async {
                    withTimeout(1_000) {
                        client.events.filterIsInstance<AgentEvent.ToolRequested>().take(2).toList()
                    }
                }
                client.sendPrompt(session, "x")
                val requested = calls.await()

                assertTrue(
                    initializeParams!!["capabilities"]!!.jsonObject["experimentalApi"]!!
                        .jsonPrimitive.boolean,
                )
                val registered = threadParams!!["dynamicTools"]!!.jsonArray
                assertEquals(listOf("read_document"), registered.map { it.jsonObject["name"]!!.jsonPrimitive.content })
                assertEquals(listOf("duplicate", "duplicate"), requested.map { it.call.id.value })

                client.submitToolResult(
                    session,
                    ToolResult.Success(
                        requested[0].call.id,
                        "{\"observed\":true}",
                        listOf("data:image/jpeg;base64,AA=="),
                    ),
                )
                client.submitToolResult(
                    session,
                    ToolResult.Rejected(requested[1].call.id, "denied"),
                )
                assertTrue(responseLatch.await(1, TimeUnit.SECONDS))
                assertTrue(responses.getValue(60)["success"]!!.jsonPrimitive.boolean)
                val content = responses.getValue(60)["contentItems"]!!.jsonArray
                assertEquals(listOf("inputText", "inputImage"), content.map {
                    it.jsonObject["type"]!!.jsonPrimitive.content
                })
                assertEquals(
                    "data:image/jpeg;base64,AA==",
                    content[1].jsonObject["imageUrl"]!!.jsonPrimitive.content,
                )
                assertFalse(responses.getValue(61)["success"]!!.jsonPrimitive.boolean)
                assertEquals(-32602, malformedCode)
                assertEquals(-32602, foreignSessionCode)
            } finally {
                client.close()
            }
        }
}
