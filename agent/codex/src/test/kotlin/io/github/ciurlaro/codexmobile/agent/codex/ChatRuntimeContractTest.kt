package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentFormValue
import io.github.ciurlaro.codexmobile.core.AgentHookTrustStatus
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.deriveConversationTitle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
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

class ChatRuntimeContractTest {
    @Test
    fun `restores only invocations recorded by the original structured message`() {
        val directory = Files.createTempDirectory("turn-inputs").toFile()
        val plugin = AgentInvocation.Plugin(
            name = "google-contacts",
            uri = "plugin://google-contacts@openai-curated",
        )
        val store = TurnInputMetadataStore(directory)
        store.upsert("thread-1", TurnInputMetadata("client-chip", listOf(plugin)))

        val messages = conversationMessages(
            listOf(
                plainUserMessage("user-chip", "client-chip", "@google-contacts\n\nFind a contact"),
                plainUserMessage("user-text", "client-text", "@someone\n\nThis is regular text"),
                plainUserMessage("user-plan", "codex-mobile:plan:client-plan", "Plan a trip"),
            ),
            store.read("thread-1"),
        )

        assertEquals("Find a contact", messages[0].text)
        assertEquals(listOf(plugin), messages[0].invocations)
        assertEquals("@someone\n\nThis is regular text", messages[1].text)
        assertTrue(messages[1].invocations.isEmpty())
        assertEquals(AgentCollaborationMode.PLAN, messages[2].collaborationMode)
    }

    @Test
    fun `plan input requests use the existing elicitation flow`(): Unit = runBlocking {
        val answer = CompletableDeferred<JsonObject>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/resume" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-1") }
                })
                null -> if (message.id == 91L) {
                    answer.complete(message.objectValue.getValue("result").jsonObject)
                }
            }
        }
        CodexAgentClient({ process }, requestTimeoutMillis = 1_000).use { client ->
            client.openSession(SessionId("thread-1"))
            val requested = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.ElicitationRequested>().first() }
            }
            process.request(91, "item/tool/requestUserInput", buildJsonObject {
                put("itemId", "item-1")
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                putJsonArray("questions") {
                    add(buildJsonObject {
                        put("header", "Dates")
                        put("id", "dates")
                        put("question", "Are your travel dates flexible?")
                        put("isOther", true)
                        putJsonArray("options") {
                            add(buildJsonObject {
                                put("label", "Flexible")
                                put("description", "Any week works")
                            })
                            add(buildJsonObject {
                                put("label", "Fixed")
                                put("description", "Use exact dates")
                            })
                        }
                    })
                }
            })

            val elicitation = requested.await()
            assertEquals("Plan", elicitation.elicitation.serverName)
            assertTrue(elicitation.elicitation.form!!.single().allowOther)
            client.resolveElicitation(
                elicitation.elicitation.requestId,
                AgentElicitationResponse(
                    AgentElicitationAction.ACCEPT,
                    mapOf("dates" to AgentFormValue.Text("Flexible")),
                ),
            )
            assertEquals(
                "Flexible",
                answer.await()["answers"]!!.jsonObject["dates"]!!.jsonObject["answers"]!!
                    .jsonArray.single().jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `lists hooks and writes only the selected hook state`(): Unit = runBlocking {
        val writes = mutableListOf<JsonObject>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "hooks/list" -> server.respond(
                    message.id,
                    buildJsonObject {
                        put("data", buildJsonArray {
                            add(buildJsonObject {
                                put("cwd", "/workspace")
                                put("warnings", buildJsonArray {})
                                put("errors", buildJsonArray {})
                                put("hooks", buildJsonArray {
                                    add(buildJsonObject {
                                        put("currentHash", "sha256:current")
                                        put("displayOrder", 0)
                                        put("enabled", false)
                                        put("eventName", "preToolUse")
                                        put("handlerType", "command")
                                        put("isManaged", false)
                                        put("key", "project-hook")
                                        put("source", "project")
                                        put("sourcePath", "/workspace/.codex/hooks.json")
                                        put("timeoutSec", 10)
                                        put("trustStatus", "untrusted")
                                        put("command", "./check")
                                    })
                                })
                            })
                        })
                    },
                )
                "config/batchWrite" -> {
                    writes += message.params
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("filePath", "/data/user/0/app/files/.codex/config.toml")
                            put("status", "ok")
                            put("version", "1")
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val hook = client.listHooks("/workspace").hooks.single()
            assertEquals(AgentHookTrustStatus.UNTRUSTED, hook.trustStatus)
            assertEquals("./check", hook.command)

            client.trustHook(hook.key, hook.currentHash)
            client.setHookEnabled(hook.key, true)

            assertEquals(2, writes.size)
            writes.forEach { assertEquals("hooks.state", it["edits"]!!.jsonArray.single().jsonObject.requiredString("keyPath")) }
            assertEquals(
                "sha256:current",
                writes[0]["edits"]!!.jsonArray.single().jsonObject["value"]!!.jsonObject
                    .getValue("project-hook").jsonObject.requiredString("trusted_hash"),
            )
            assertTrue(
                writes[1]["edits"]!!.jsonArray.single().jsonObject["value"]!!.jsonObject
                    .getValue("project-hook").jsonObject.requiredBoolean("enabled"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `renames and deletes conversations through stable thread methods`(): Unit = runBlocking {
        var renameParams: JsonObject? = null
        var deleteParams: JsonObject? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/name/set" -> {
                    renameParams = message.params
                    server.respond(message.id, buildJsonObject {})
                }

                "thread/delete" -> {
                    deleteParams = message.params
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val sessionId = SessionId("thread-history")
            client.renameSession(sessionId, "  Useful name  ")
            client.deleteSession(sessionId)

            assertEquals("thread-history", checkNotNull(renameParams).requiredString("threadId"))
            assertEquals("Useful name", checkNotNull(renameParams).requiredString("name"))
            assertEquals("thread-history", checkNotNull(deleteParams).requiredString("threadId"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `runs a leading bang through the native user shell stream`(): Unit = runBlocking {
        val transcriptDirectory = Files.createTempDirectory("shell-transcript-test").toFile()
        var startParams: JsonObject? = null
        var shellParams: JsonObject? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> {
                    startParams = message.params
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("thread") { put("id", "thread-shell") } },
                    )
                }

                "thread/shellCommand" -> {
                    shellParams = message.params
                    server.respond(message.id, buildJsonObject {})
                    server.notify(
                        "item/started",
                        buildJsonObject {
                            put("threadId", "thread-shell")
                            put("turnId", "turn-shell")
                            putJsonObject("item") {
                                put("command", "printf 'one\\ntwo\\n'")
                                put("commandActions", buildJsonArray {})
                                put("cwd", "/storage/emulated/0/Documents")
                                put("id", "command-shell")
                                put("type", "commandExecution")
                                put("source", "userShell")
                                put("status", "inProgress")
                            }
                        },
                    )
                    server.notify(
                        "item/commandExecution/outputDelta",
                        buildJsonObject {
                            put("threadId", "thread-shell")
                            put("turnId", "turn-shell")
                            put("itemId", "command-shell")
                            put("delta", "one\ntwo\n")
                        },
                    )
                    server.notify(
                        "item/completed",
                        buildJsonObject {
                            put("threadId", "thread-shell")
                            put("turnId", "turn-shell")
                            putJsonObject("item") {
                                put("command", "printf 'one\\ntwo\\n'")
                                put("commandActions", buildJsonArray {})
                                put("cwd", "/storage/emulated/0/Documents")
                                put("id", "command-shell")
                                put("type", "commandExecution")
                                put("source", "userShell")
                                put("status", "completed")
                                put("aggregatedOutput", "one\ntwo\n")
                                put("exitCode", 0)
                            }
                        },
                    )
                    server.notify(
                        "turn/completed",
                        buildJsonObject {
                            put("threadId", "thread-shell")
                            putJsonObject("turn") {
                                put("id", "turn-shell")
                                put("status", "completed")
                            }
                        },
                    )
                }

                "thread/list" -> server.respond(
                    message.id,
                    page(listOf(thread("thread-shell", null, "", 30)), null),
                )

                "thread/read" -> server.respond(
                    message.id,
                    buildJsonObject {
                        put("thread", thread(
                            id = "thread-shell",
                            name = null,
                            preview = "",
                            updatedAt = 30,
                            turns = buildJsonArray {
                                add(buildJsonObject {
                                    put("id", "turn-shell")
                                    put("items", buildJsonArray {})
                                    put("status", "completed")
                                })
                            },
                        ))
                    },
                )
            }
        }
        val client = CodexAgentClient(
            { process },
            requestTimeoutMillis = 1_000,
            shellTranscriptDirectory = transcriptDirectory,
        )
        try {
            val session = client.openSession(
                settings = AgentRuntimeSettings(workingDirectory = "/storage/emulated/0/Documents"),
            )
            val events = async {
                withTimeout(1_000) {
                    client.events.filter {
                        it is AgentEvent.ShellOutputDelta ||
                            it is AgentEvent.ShellCommandCompleted ||
                            it is AgentEvent.TurnCompleted
                    }.take(3).toList()
                }
            }

            client.runShellCommand(session, "printf 'one\\ntwo\\n'")

            assertEquals("/storage/emulated/0/Documents", checkNotNull(startParams).requiredString("cwd"))
            assertEquals("thread-shell", checkNotNull(shellParams).requiredString("threadId"))
            assertEquals("printf 'one\\ntwo\\n'", checkNotNull(shellParams).requiredString("command"))
            val received = events.await()
            assertEquals("one\ntwo\n", assertIs<AgentEvent.ShellOutputDelta>(received[0]).text)
            assertEquals(0, assertIs<AgentEvent.ShellCommandCompleted>(received[1]).exitCode)
            assertIs<AgentEvent.TurnCompleted>(received[2])

            assertEquals("!printf 'one\\ntwo\\n'", client.listSessions().single().title)
            val history = client.readSession(session)
            assertEquals(listOf(AgentMessageRole.USER, AgentMessageRole.CODEX), history.messages.map { it.role })
            assertEquals("!printf 'one\\ntwo\\n'", history.messages[0].text)
            assertEquals("printf 'one\\ntwo\\n'", history.messages[1].shellCommand)
            assertEquals("one\ntwo\n", history.messages[1].text)
            assertEquals(0, history.messages[1].exitCode)
        } finally {
            client.close()
            transcriptDirectory.deleteRecursively()
        }
    }

    @Test
    fun `discovers paged models and conversation history from app-server protocol`(): Unit = runBlocking {
        val modelCursors = mutableListOf<String?>()
        val threadCursors = mutableListOf<String?>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "model/list" -> {
                    val cursor = message.params.optionalString("cursor")
                    modelCursors += cursor
                    server.respond(
                        message.id,
                        page(
                            data = listOf(
                                if (cursor == null) model("catalog-a", "runtime-a", "Model A", "low", true)
                                else model("catalog-b", "runtime-b", "Model B", "xhigh", false),
                            ),
                            nextCursor = if (cursor == null) "models-2" else null,
                        ),
                    )
                }

                "thread/list" -> {
                    assertEquals("updated_at", message.params.requiredString("sortKey"))
                    assertEquals("desc", message.params.requiredString("sortDirection"))
                    val cursor = message.params.optionalString("cursor")
                    threadCursors += cursor
                    server.respond(
                        message.id,
                        page(
                            data = listOf(
                                if (cursor == null) thread("thread-a", "Pinned title", "ignored", 20)
                                else thread(
                                    "thread-b",
                                    null,
                                    "${AgentCapability.WEB_SEARCH.promptLabel}\n\nSecond title\nbody",
                                    10,
                                ),
                            ),
                            nextCursor = if (cursor == null) "threads-2" else null,
                        ),
                    )
                }

                "thread/read" -> {
                    assertEquals("thread-b", message.params.requiredString("threadId"))
                    assertTrue(message.params.requiredBoolean("includeTurns"))
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put(
                                "thread",
                                thread(
                                    id = "thread-b",
                                    name = null,
                                    preview = "${AgentCapability.WEB_SEARCH.promptLabel}\n\nQuestion",
                                    updatedAt = 10,
                                    turns = buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "items",
                                                    buildJsonArray {
                                                        add(taggedUserMessage("user-1", "client-1", "Question"))
                                                        add(
                                                            buildJsonObject {
                                                                put("id", "reasoning-1")
                                                                put("type", "reasoning")
                                                                put("summary", buildJsonArray {
                                                                    add(JsonPrimitive("Checked the sources"))
                                                                })
                                                            },
                                                        )
                                                        add(
                                                            buildJsonObject {
                                                                put("id", "search-1")
                                                                put("type", "webSearch")
                                                                put("query", "Question")
                                                            },
                                                        )
                                                        add(
                                                            buildJsonObject {
                                                                put("id", "commentary-1")
                                                                put("type", "agentMessage")
                                                                put("phase", "commentary")
                                                                put("text", "Checking the result")
                                                            },
                                                        )
                                                        add(
                                                            buildJsonObject {
                                                                put("id", "codex-1")
                                                                put("type", "agentMessage")
                                                                put("phase", "final_answer")
                                                                put("text", "Answer")
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val models = client.listModels()
            assertEquals(listOf(null, "models-2"), modelCursors)
            assertEquals(listOf("runtime-a", "runtime-b"), models.map { it.id })
            assertEquals(listOf("low", "medium"), models.first().supportedEfforts)
            assertEquals("low", models.first().defaultEffort)
            assertTrue(models.first().isDefault)

            val summaries = client.listSessions()
            assertEquals(listOf(null, "threads-2"), threadCursors)
            assertEquals(listOf("Pinned title", "Second title"), summaries.map { it.title })

            val conversation = client.readSession(SessionId("thread-b"))
            assertEquals("Question", conversation.summary.title)
            assertEquals(2, conversation.messages.size)
            val user = conversation.messages[0]
            assertEquals("user-1", user.id)
            assertEquals("client-1", user.clientId)
            assertEquals(AgentMessageRole.USER, user.role)
            assertEquals("Question", user.text)
            assertEquals(setOf(AgentCapability.WEB_SEARCH), user.capabilities)
            assertEquals(AgentMessageRole.CODEX, conversation.messages[1].role)
            assertEquals("Answer", conversation.messages[1].text)
            assertEquals("Checked the sources\n\nChecking the result", conversation.messages[1].reasoning)
        } finally {
            client.close()
        }
    }

    @Test
    fun `resumes settings and snapshots a structured Web Search turn`(): Unit = runBlocking {
        var resumeParams: JsonObject? = null
        var turnParams: JsonObject? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/resume" -> {
                    resumeParams = message.params
                    server.respond(
                        message.id,
                        buildJsonObject {
                            putJsonObject("thread") { put("id", "thread-1") }
                            put("model", "runtime-model")
                        },
                    )
                }

                "turn/start" -> {
                    turnParams = message.params
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                    )
                    server.notify(
                        "item/reasoning/summaryTextDelta",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            put("turnId", "turn-1")
                            put("itemId", "reasoning-1")
                            put("summaryIndex", 0)
                            put("delta", "Inspecting")
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val opened = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.SessionOpened>().first() }
            }
            client.openSession(SessionId("thread-1"))
            assertEquals(
                AgentEvent.SessionOpened(SessionId("thread-1"), "runtime-model", null),
                opened.await(),
            )

            val resume = checkNotNull(resumeParams)
            assertEquals("thread-1", resume.requiredString("threadId"))
            val config = resume["config"]!!.jsonObject
            assertEquals("live", config.requiredString("web_search"))
            assertTrue(
                config["tools"]!!.jsonObject["experimental_request_user_input"]!!
                    .jsonObject.requiredBoolean("enabled"),
            )
            val features = config["features"]!!.jsonObject
            assertFalse("web_search_request" in features)
            assertFalse("web_search_cached" in features)
            assertFalse(features.requiredBoolean("standalone_web_search"))

            val reasoning = async {
                withTimeout(1_000) {
                    client.events.filterIsInstance<AgentEvent.ReasoningSummaryDelta>().first()
                }
            }
            client.sendTurn(
                SessionId("thread-1"),
                AgentTurnRequest(
                    prompt = "Find the current answer",
                    clientMessageId = "client-message-1",
                    model = "runtime-model-next",
                    effort = "xhigh",
                    capabilities = setOf(AgentCapability.WEB_SEARCH),
                    workingDirectory = "/storage/emulated/0/Documents",
                    collaborationMode = AgentCollaborationMode.PLAN,
                ),
            )

            val turn = checkNotNull(turnParams)
            assertEquals("client-message-1", turn.requiredString("clientUserMessageId"))
            assertEquals("runtime-model-next", turn.requiredString("model"))
            assertEquals("xhigh", turn.requiredString("effort"))
            assertEquals("/storage/emulated/0/Documents", turn.requiredString("cwd"))
            assertEquals("auto", turn.requiredString("summary"))
            val collaborationMode = turn["collaborationMode"]!!.jsonObject
            assertEquals("plan", collaborationMode.requiredString("mode"))
            val modeSettings = collaborationMode["settings"]!!.jsonObject
            assertEquals("runtime-model-next", modeSettings.requiredString("model"))
            assertEquals("medium", modeSettings.requiredString("reasoning_effort"))
            assertFalse("developer_instructions" in modeSettings)
            assertEquals("Inspecting", reasoning.await().text)
            val input = turn["input"]!!.jsonArray.single().jsonObject
            val expectedText = "${AgentCapability.WEB_SEARCH.promptLabel}\n\nFind the current answer"
            assertEquals(expectedText, input.requiredString("text"))
            val element = input["text_elements"]!!.jsonArray.single().jsonObject
            assertEquals(AgentCapability.WEB_SEARCH.displayLabel, element.requiredString("placeholder"))
            val range = element["byteRange"]!!.jsonObject
            assertEquals(0, range.requiredInt("start"))
            assertEquals(
                AgentCapability.WEB_SEARCH.promptLabel.toByteArray(StandardCharsets.UTF_8).size,
                range.requiredInt("end"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `derives one bounded title from explicit name or first user line`() {
        assertEquals("Named", deriveConversationTitle("  Named  ", "ignored"))
        assertEquals("First line", deriveConversationTitle(null, "\n First line \nsecond"))
        assertEquals("New chat", deriveConversationTitle(null, " \n "))
        assertEquals("abcd", deriveConversationTitle(null, "abcdef", maxLength = 4))
    }
}

private val ClientMessage.params: JsonObject
    get() = objectValue["params"]!!.jsonObject

private fun model(
    catalogId: String,
    runtimeId: String,
    displayName: String,
    defaultEffort: String,
    isDefault: Boolean,
) = buildJsonObject {
    put("id", catalogId)
    put("model", runtimeId)
    put("displayName", displayName)
    put("description", "$displayName description")
    put(
        "supportedReasoningEfforts",
        buildJsonArray {
            add(buildJsonObject { put("reasoningEffort", "low"); put("description", "Low") })
            add(buildJsonObject { put("reasoningEffort", "medium"); put("description", "Medium") })
        },
    )
    put("defaultReasoningEffort", defaultEffort)
    put("isDefault", isDefault)
}

private fun page(data: List<JsonObject>, nextCursor: String?) = buildJsonObject {
    put("data", buildJsonArray { data.forEach { add(it) } })
    if (nextCursor == null) put("nextCursor", JsonNull) else put("nextCursor", nextCursor)
}

private fun thread(
    id: String,
    name: String?,
    preview: String,
    updatedAt: Long,
    turns: kotlinx.serialization.json.JsonArray = buildJsonArray {},
) = buildJsonObject {
    put("id", id)
    if (name == null) put("name", JsonNull) else put("name", name)
    put("preview", preview)
    put("updatedAt", updatedAt)
    put("turns", turns)
}

private fun taggedUserMessage(id: String, clientId: String, prompt: String): JsonObject {
    val capability = AgentCapability.WEB_SEARCH
    return buildJsonObject {
        put("id", id)
        put("clientId", clientId)
        put("type", "userMessage")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "${capability.promptLabel}\n\n$prompt")
                        put(
                            "text_elements",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        putJsonObject("byteRange") {
                                            put("start", 0)
                                            put(
                                                "end",
                                                capability.promptLabel
                                                    .toByteArray(StandardCharsets.UTF_8)
                                                    .size,
                                            )
                                        }
                                        put("placeholder", capability.displayLabel)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }
}

private fun plainUserMessage(id: String, clientId: String, text: String) = buildJsonObject {
    put("id", id)
    put("clientId", clientId)
    put("type", "userMessage")
    put("content", buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
        })
    })
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: error("Missing $name")

private fun JsonObject.optionalString(name: String): String? =
    this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.requiredBoolean(name: String): Boolean =
    requiredString(name).toBooleanStrict()

private fun JsonObject.requiredInt(name: String): Int = requiredString(name).toInt()
