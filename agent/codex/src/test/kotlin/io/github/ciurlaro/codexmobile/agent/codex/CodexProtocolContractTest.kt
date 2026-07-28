package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class CodexProtocolContractTest {
    @Test
    fun `frames partial batched CRLF and UTF-8 input`() {
        val unicode = "Grüezi 👋"
        val bytes = "{\"n\":1}\r\n{\"text\":\"$unicode\"}\n".toByteArray(StandardCharsets.UTF_8)
        val lines = mutableListOf<String>()

        readUtf8JsonLines(ChunkedInputStream(bytes, 2), onLine = lines::add)

        assertEquals(listOf("{\"n\":1}", "{\"text\":\"$unicode\"}"), lines)
    }

    @Test
    fun `fuzzed frames stay bounded and malformed UTF-8 fails closed`() {
        val random = Random(6)
        val elapsed = measureTimeMillis {
            repeat(2_048) {
                val input = ByteArray(random.nextInt(0, 4_096)).also(random::nextBytes)
                try {
                    readUtf8JsonLines(ByteArrayInputStream(input), maxBytes = 4_096) { line ->
                        assertTrue(line.toByteArray(StandardCharsets.UTF_8).size <= 4_096)
                    }
                } catch (_: Exception) {
                    // Random malformed frames are expected; assertion errors must still fail the test.
                }
            }
            repeat(64) {
                assertFailsWith<IllegalStateException> {
                    readUtf8JsonLines(ByteArrayInputStream(ByteArray(4_097) { 'x'.code.toByte() }), 4_096) {}
                }
            }
            listOf(
                byteArrayOf(0xC3.toByte(), 0x28),
                byteArrayOf(0xA0.toByte(), 0xA1.toByte()),
                byteArrayOf(0xE2.toByte(), 0x28, 0xA1.toByte()),
                byteArrayOf(0xF0.toByte(), 0x28, 0x8C.toByte(), 0xBC.toByte()),
            ).forEach { bytes ->
                assertFailsWith<Exception> { readUtf8JsonLines(ByteArrayInputStream(bytes)) {} }
            }
        }
        assertTrue(elapsed < 5_000, "fuzz elapsed ${elapsed}ms")
    }

    @Test
    fun `correlates responses while preserving notification order`(): Unit = runBlocking {
        val launches = AtomicInteger()
        var accountId: Long? = null
        var threadId: Long? = null
        var threadParams: JsonObject? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> accountId = message.id
                "thread/start" -> {
                    threadId = message.id
                    threadParams = message.objectValue["params"]!!.jsonObject
                }
            }
            if (accountId != null && threadId != null) {
                server.notify("unknown/notification", buildJsonObject {})
                server.respond(
                    threadId,
                    buildJsonObject { putJsonObject("thread") { put("id", "thread-1") } },
                )
                server.respond(
                    accountId,
                    buildJsonObject {
                        putJsonObject("account") { put("type", "chatgpt") }
                        put("requiresOpenaiAuth", true)
                    },
                )
                accountId = null
                threadId = null
            }
        }
        val client = CodexAgentClient(
            { launches.incrementAndGet(); process },
            requestTimeoutMillis = 1_000,
        )
        try {
            coroutineScope {
                val auth = async { client.authenticate() }
                val session = async { client.openSession() }
                auth.await()
                assertEquals(SessionId("thread-1"), session.await())
            }
            assertEquals(1, launches.get())
            val params = checkNotNull(threadParams)
            assertEquals("on-request", params["approvalPolicy"]!!.jsonPrimitive.content)
            assertEquals("auto_review", params["approvalsReviewer"]!!.jsonPrimitive.content)
            assertEquals("danger-full-access", params["sandbox"]!!.jsonPrimitive.content)
            val instructions = params["developerInstructions"]!!.jsonPrimitive.content
            assertFalse("raw argv" in instructions)
            assertTrue("advertised typed contracts" in instructions)
            val config = params["config"]!!.jsonObject
            assertEquals("live", config["web_search"]!!.jsonPrimitive.content)
            assertEquals(
                true,
                config["tools"]!!.jsonObject["experimental_request_user_input"]!!
                    .jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean(),
            )
            val features = config["features"]!!.jsonObject
            listOf(
                "code_mode",
                "multi_agent",
                "image_generation",
                "goals",
                "skill_mcp_dependency_install",
                "workspace_dependencies",
            ).forEach { feature ->
                assertEquals(false, features[feature]!!.jsonPrimitive.content.toBoolean())
            }
            listOf("apps", "enable_mcp_apps", "plugins", "hooks").forEach { feature ->
                assertEquals(true, features[feature]!!.jsonPrimitive.content.toBoolean())
            }
            assertEquals(true, features["shell_tool"]!!.jsonPrimitive.content.toBoolean())
            val shellEnvironment = config["shell_environment_policy"]!!.jsonObject
            assertEquals("all", shellEnvironment["inherit"]!!.jsonPrimitive.content)
            assertTrue(
                "HTTPS_PROXY" in shellEnvironment["exclude"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `sign out uses the account endpoint and clears in-memory authentication`(): Unit = runBlocking {
        val accountReads = AtomicInteger()
        var logoutParams: JsonObject? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> {
                    accountReads.incrementAndGet()
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("account") { put("type", "chatgpt") } },
                    )
                }
                "account/logout" -> {
                    logoutParams = message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            client.authenticate()
            client.signOut()
            client.authenticate()

            assertEquals(2, accountReads.get())
            assertTrue(checkNotNull(logoutParams).isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun `drains stderr and closes the process and all streams`(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> {
                    server.sendStderr("stderr is not JSON and must never enter stdout")
                    server.respond(message.id, buildJsonObject {})
                }
                "account/read" -> server.respond(
                    message.id,
                    buildJsonObject {
                        putJsonObject("account") { put("type", "chatgpt") }
                        put("requiresOpenaiAuth", true)
                    },
                )
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)

        client.authenticate()
        client.close()

        assertFalse(process.isAlive)
        assertTrue(process.allClientStreamsClosed())
    }

    @Test
    fun `turns process start exit and EOF into one typed failure`(): Unit = runBlocking {
        val startClient = CodexAgentClient(
            { error("cannot execute bundled runtime") },
            requestTimeoutMillis = 1_000,
        )
        try {
            val failure = async {
                withTimeout(1_000) { startClient.events.filterIsInstance<AgentEvent.Failure>().first() }
            }
            assertFailsWith<IllegalStateException> { startClient.authenticate() }
            assertEquals("process_start", failure.await().code)
        } finally {
            startClient.close()
        }

        val exited = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.exit(23)
            }
        }
        val exitClient = CodexAgentClient({ exited }, requestTimeoutMillis = 1_000)
        try {
            val failure = async {
                withTimeout(1_000) { exitClient.events.filterIsInstance<AgentEvent.Failure>().first() }
            }
            assertFailsWith<IllegalStateException> { exitClient.authenticate() }
            assertEquals("process_exit", failure.await().code)
        } finally {
            exitClient.close()
        }

        val eof = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.closeStdout()
            }
        }
        val eofClient = CodexAgentClient({ eof }, requestTimeoutMillis = 1_000)
        try {
            val failure = async {
                withTimeout(1_000) { eofClient.events.filterIsInstance<AgentEvent.Failure>().first() }
            }
            assertFailsWith<IllegalStateException> { eofClient.authenticate() }
            assertEquals("unexpected_eof", failure.await().code)
        } finally {
            eofClient.close()
        }
    }

    @Test
    fun `a dead process can restart and recheck authentication`(): Unit = runBlocking {
        val processes = mutableListOf<FakeCodexRuntime>()
        val client = CodexAgentClient(
            {
                FakeCodexRuntime { message, server ->
                    when (message.method) {
                        "initialize" -> server.respond(message.id, buildJsonObject {})
                        "account/read" -> server.respond(
                            message.id,
                            buildJsonObject {
                                putJsonObject("account") { put("type", "chatgpt") }
                                put("requiresOpenaiAuth", true)
                            },
                        )
                    }
                }.also(processes::add)
            },
            requestTimeoutMillis = 1_000,
        )
        try {
            val events = async { withTimeout(5_000) { client.events.take(3).toList() } }
            client.authenticate()
            processes.single().exit(9)
            client.authenticate()

            val received = events.await()
            assertIs<AgentEvent.Authenticated>(received[0])
            assertIs<AgentEvent.Failure>(received[1])
            assertIs<AgentEvent.Authenticated>(received[2])
            assertEquals(2, processes.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun `terminal failure closes runtime while event delivery is backpressured`(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "skills/list" -> {
                    server.respond(message.id, buildJsonObject { put("data", buildJsonArray {}) })
                    repeat(64) { server.notify("skills/changed", buildJsonObject {}) }
                    server.sendRaw("{")
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            client.listSkills("/workspace")
            withTimeout(1_000) {
                while (!process.allClientStreamsClosed()) kotlinx.coroutines.yield()
            }

            val events = withTimeout(1_000) { client.events.take(65).toList() }
            assertTrue(events.take(64).all { it is AgentEvent.SkillsChanged })
            assertEquals("protocol_failure", assertIs<AgentEvent.Failure>(events.last()).code)
        } finally {
            client.close()
        }
    }

    @Test
    fun `rejects malformed unknown and orphan messages without deadlock`(): Unit = runBlocking {
        val requestRejected = CountDownLatch(1)
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> {
                    server.sendRaw("{\"id\":999,\"result\":{}}")
                    server.notify("future/method", buildJsonObject { put("extra", true) })
                    server.sendRaw("{\"id\":800,\"method\":\"future/request\",\"params\":{}}")
                    server.respond(
                        message.id,
                        buildJsonObject {
                            putJsonObject("account") { put("type", "chatgpt") }
                            put("requiresOpenaiAuth", true)
                        },
                    )
                }
                null -> if (
                    message.id == 800L &&
                    message.objectValue["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content == "-32601"
                ) {
                    requestRejected.countDown()
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            withTimeout(1_000) { client.authenticate() }
            assertTrue(requestRejected.await(1, TimeUnit.SECONDS))
        } finally {
            client.close()
        }

        val malformed = FakeCodexRuntime { message, server ->
            if (message.method == "initialize") server.sendRaw("not-json")
        }
        val malformedClient = CodexAgentClient({ malformed }, requestTimeoutMillis = 1_000)
        try {
            assertFailsWith<Exception> { malformedClient.authenticate() }
        } finally {
            malformedClient.close()
        }
    }

    @Test
    fun `bounds large messages slow consumers and cancellation races`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> {
            readUtf8JsonLines(ByteArrayInputStream("12345".toByteArray()), maxBytes = 4) {}
        }

        val interruptReceived = CountDownLatch(1)
        val releaseInterrupt = CountDownLatch(1)
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> server.respond(
                    message.id,
                    buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                )
                "turn/interrupt" -> {
                    interruptReceived.countDown()
                    check(releaseInterrupt.await(1, TimeUnit.SECONDS))
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val session = SessionId("thread-1")
            client.sendTurn(session, AgentTurnRequest("hello"))
            coroutineScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) { client.cancelTurn(session) }
                withTimeout(1_000) {
                    while (!interruptReceived.await(10, TimeUnit.MILLISECONDS)) {
                        if (first.isCompleted) first.await()
                        kotlinx.coroutines.yield()
                    }
                }
                val second = runCatching { client.cancelTurn(session) }
                releaseInterrupt.countDown()
                first.await()
                assertTrue(second.isFailure)
            }
        } finally {
            releaseInterrupt.countDown()
            client.close()
        }
    }

    @Test
    fun `a terminal notification racing the start response leaves the client usable`(): Unit = runBlocking {
        val turnIds = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> {
                    val turnId = "turn-${turnIds.incrementAndGet()}"
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", turnId) } },
                    )
                    server.notify(
                        "turn/completed",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            putJsonObject("turn") {
                                put("id", turnId)
                                put("status", "completed")
                            }
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val session = SessionId("thread-1")
        try {
            repeat(20) {
                val completed = async {
                    withTimeout(1_000) {
                        client.events.filterIsInstance<AgentEvent.TurnCompleted>().first()
                    }
                }
                client.sendTurn(session, AgentTurnRequest("fast turn"))
                completed.await()
                assertFailsWith<IllegalStateException> { client.cancelTurn(session) }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `interrupt after provider completion is harmless`(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> server.respond(
                    message.id,
                    buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                )
                "turn/interrupt" -> {
                    server.sendRaw(
                        buildJsonObject {
                            put("id", message.id)
                            putJsonObject("error") {
                                put("code", -32600)
                                put("message", "no active turn to interrupt")
                            }
                        }.toString(),
                    )
                    server.notify(
                        "turn/completed",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            putJsonObject("turn") {
                                put("id", "turn-1")
                                put("status", "completed")
                            }
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val completed = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.TurnCompleted>().first() }
            }
            client.sendTurn(SessionId("thread-1"), AgentTurnRequest("hello"))
            client.cancelTurn(SessionId("thread-1"))
            assertEquals(SessionId("thread-1"), completed.await().sessionId)
        } finally {
            client.close()
        }
    }

    @Test
    fun `slow event consumers fail explicitly instead of blocking the runtime reader`(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> {
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                    )
                    repeat(2_000) {
                        server.notify(
                            "item/agentMessage/delta",
                            buildJsonObject {
                                put("threadId", "thread-1")
                                put("turnId", "turn-1")
                                put("itemId", "item-1")
                                put("delta", "x")
                            },
                        )
                    }
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            client.sendTurn(SessionId("thread-1"), AgentTurnRequest("hello"))
            withTimeout(2_000) {
                while (process.isAlive) kotlinx.coroutines.yield()
            }
            val failure = withTimeout(5_000) {
                client.events.filterIsInstance<AgentEvent.Failure>().first()
            }
            assertTrue(failure.message.contains("event buffer exceeded"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `translates authentication session stream completion and failure events`(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.respond(
                    message.id,
                    buildJsonObject {
                        put("account", JsonNull)
                        put("requiresOpenaiAuth", true)
                    },
                )
                "account/login/start" -> {
                    val params = message.objectValue["params"]!!.jsonObject
                    assertEquals(setOf("type", "useHostedLoginSuccessPage", "appBrand"), params.keys)
                    assertEquals("chatgpt", params["type"]!!.jsonPrimitive.content)
                    assertEquals("true", params["useHostedLoginSuccessPage"]!!.jsonPrimitive.content)
                    assertEquals("codex", params["appBrand"]!!.jsonPrimitive.content)
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("type", "chatgpt")
                            put("loginId", "login-1")
                            put("authUrl", "https://auth.openai.com/oauth/authorize?state=test")
                        },
                    )
                }
                "thread/start" -> server.respond(
                    message.id,
                    buildJsonObject { putJsonObject("thread") { put("id", "thread-1") } },
                )
                "turn/start" -> {
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                    )
                    server.notify(
                        "item/started",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            put("turnId", "turn-1")
                            putJsonObject("item") {
                                put("id", "item-1")
                                put("type", "agentMessage")
                                put("phase", "commentary")
                                put("text", "")
                            }
                        },
                    )
                    server.notify(
                        "item/agentMessage/delta",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            put("turnId", "turn-1")
                            put("itemId", "item-1")
                            put("delta", "Hello")
                        },
                    )
                    server.notify(
                        "turn/completed",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            putJsonObject("turn") {
                                put("id", "turn-1")
                                put("status", "completed")
                            }
                        },
                    )
                    server.notify(
                        "error",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            put("turnId", "turn-2")
                            put("willRetry", false)
                            putJsonObject("error") { put("message", "offline") }
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val received = mutableListOf<AgentEvent>()
            val collector = async { withTimeout(2_000) { client.events.take(6).toList(received) } }
            client.authenticate()
            process.notify(
                "account/login/completed",
                buildJsonObject {
                    put("loginId", "login-1")
                    put("success", true)
                    put("error", JsonNull)
                },
            )
            val session = client.openSession()
            client.sendTurn(session, AgentTurnRequest("hello"))
            collector.await()

            val required = assertIs<AgentEvent.AuthenticationRequired>(received[0])
            assertEquals("https://auth.openai.com/oauth/authorize?state=test", required.signInUrl)
            assertIs<AgentEvent.Authenticated>(received[1])
            assertEquals(AgentEvent.SessionOpened(SessionId("thread-1"), model = "test"), received[2])
            assertEquals(
                AgentEvent.TextDelta(SessionId("thread-1"), "Hello", "item-1", isCommentary = true),
                received[3],
            )
            assertEquals(AgentEvent.TurnCompleted(SessionId("thread-1")), received[4])
            assertIs<AgentEvent.Failure>(received[5])
        } finally {
            client.close()
        }
    }

    @Test
    fun `failed authentication can be retried without conflicting login state`(): Unit = runBlocking {
        val loginAttempts = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.respond(
                    message.id,
                    buildJsonObject {
                        put("account", JsonNull)
                        put("requiresOpenaiAuth", true)
                    },
                )
                "account/login/start" -> {
                    val attempt = loginAttempts.incrementAndGet()
                    val loginId = "login-$attempt"
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("type", "chatgpt")
                            put("loginId", loginId)
                            put("authUrl", "https://auth.openai.com/oauth/authorize?state=$loginId")
                        },
                    )
                    server.notify(
                        "account/login/completed",
                        buildJsonObject {
                            put("loginId", loginId)
                            put("success", false)
                            put("error", "expired")
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            repeat(2) {
                val failed = async {
                    withTimeout(1_000) {
                        client.events.filterIsInstance<AgentEvent.Failure>().first()
                    }
                }
                client.authenticate()
                assertEquals("authentication_failed", failed.await().code)
            }
            assertEquals(2, loginAttempts.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun `cancelled authentication suppresses the expected failure and can be retried`(): Unit = runBlocking {
        val loginAttempts = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.respond(
                    message.id,
                    buildJsonObject {
                        put("account", JsonNull)
                        put("requiresOpenaiAuth", true)
                    },
                )
                "account/login/start" -> {
                    val loginId = "login-${loginAttempts.incrementAndGet()}"
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("type", "chatgpt")
                            put("loginId", loginId)
                            put("authUrl", "https://auth.openai.com/oauth/authorize?state=$loginId")
                        },
                    )
                }
                "account/login/cancel" -> {
                    val loginId = message.objectValue["params"]!!.jsonObject["loginId"]!!.jsonPrimitive.content
                    server.notify(
                        "account/login/completed",
                        buildJsonObject {
                            put("loginId", loginId)
                            put("success", false)
                            put("error", "cancelled")
                        },
                    )
                    server.respond(message.id, buildJsonObject { put("status", "canceled") })
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val events = mutableListOf<AgentEvent>()
            val collector = async { client.events.take(2).toList(events) }
            client.authenticate()
            client.cancelAuthentication()
            client.authenticate()
            withTimeout(1_000) { collector.await() }

            assertEquals(2, loginAttempts.get())
            assertTrue(events.all { it is AgentEvent.AuthenticationRequired })
        } finally {
            client.close()
        }
    }

    @Test
    fun `authentication timeout is bounded and retryable`(): Unit = runBlocking {
        val reads = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> if (reads.incrementAndGet() > 1) {
                    server.respond(
                        message.id,
                        buildJsonObject {
                            putJsonObject("account") { put("type", "chatgpt") }
                            put("requiresOpenaiAuth", true)
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 50)
        try {
            assertFailsWith<Exception> { client.authenticate() }
            val authenticated = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.Authenticated>().first() }
            }
            client.authenticate()
            authenticated.await()
            assertEquals(2, reads.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun `rejects blank prompts and preserves Unicode and multiline prompts`(): Unit = runBlocking {
        val neverStarted = CodexAgentClient({ error("must not launch") })
        assertFailsWith<IllegalArgumentException> {
            neverStarted.sendTurn(SessionId("thread"), AgentTurnRequest("  \n"))
        }
        assertFailsWith<IllegalArgumentException> {
            neverStarted.sendTurn(
                SessionId("thread"),
                AgentTurnRequest("x".repeat(100_001)),
            )
        }
        neverStarted.close()

        var observedPrompt: String? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> {
                    observedPrompt = message.objectValue["params"]!!.jsonObject["input"]!!
                        .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", "turn") } },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val prompt = "Grüezi 👋\n第二行"
            client.sendTurn(SessionId("thread"), AgentTurnRequest(prompt))
            assertEquals(prompt, observedPrompt)
        } finally {
            client.close()
        }
    }
}

private class ChunkedInputStream(
    bytes: ByteArray,
    private val chunkSize: Int,
) : ByteArrayInputStream(bytes) {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, minOf(length, chunkSize))
}
