package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.SessionId
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class Step01ProtocolContractTest {
    @Test
    fun `frames partial batched CRLF and UTF-8 input`() {
        val unicode = "Grüezi 👋"
        val bytes = "{\"n\":1}\r\n{\"text\":\"$unicode\"}\n".toByteArray(StandardCharsets.UTF_8)
        val lines = mutableListOf<String>()

        readUtf8JsonLines(ChunkedInputStream(bytes, 2), onLine = lines::add)

        assertEquals(listOf("{\"n\":1}", "{\"text\":\"$unicode\"}"), lines)
    }

    @Test
    fun `correlates responses while preserving notification order`(): Unit = runBlocking {
        val launches = AtomicInteger()
        var accountId: Long? = null
        var threadId: Long? = null
        var threadParams: JsonObject? = null
        val process = ScriptedProcess { message, server ->
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
            { _, _ -> launches.incrementAndGet(); process },
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
            assertEquals("never", params["approvalPolicy"]!!.jsonPrimitive.content)
            assertEquals("read-only", params["sandbox"]!!.jsonPrimitive.content)
            val config = params["config"]!!.jsonObject
            assertEquals("disabled", config["web_search"]!!.jsonPrimitive.content)
            assertEquals(
                false,
                config["tools"]!!.jsonObject["experimental_request_user_input"]!!
                    .jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean(),
            )
            val features = config["features"]!!.jsonObject
            listOf(
                "shell_tool",
                "code_mode",
                "multi_agent",
                "apps",
                "enable_mcp_apps",
                "plugins",
                "image_generation",
                "goals",
                "hooks",
                "skill_mcp_dependency_install",
                "workspace_dependencies",
            ).forEach { feature ->
                assertEquals(false, features[feature]!!.jsonPrimitive.content.toBoolean())
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `drains stderr and closes the process and all streams`(): Unit = runBlocking {
        val process = ScriptedProcess { message, server ->
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
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 1_000)

        client.authenticate()
        client.close()

        assertFalse(process.isAlive)
        assertTrue(process.allClientStreamsClosed())
    }

    @Test
    fun `turns process start exit and EOF into one typed failure`(): Unit = runBlocking {
        val startClient = CodexAgentClient(
            { _, _ -> error("cannot execute bundled runtime") },
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

        val exited = ScriptedProcess { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.exit(23)
            }
        }
        val exitClient = CodexAgentClient({ _, _ -> exited }, requestTimeoutMillis = 1_000)
        try {
            val failure = async {
                withTimeout(1_000) { exitClient.events.filterIsInstance<AgentEvent.Failure>().first() }
            }
            assertFailsWith<IllegalStateException> { exitClient.authenticate() }
            assertEquals("process_exit", failure.await().code)
        } finally {
            exitClient.close()
        }

        val eof = ScriptedProcess { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.closeStdout()
            }
        }
        val eofClient = CodexAgentClient({ _, _ -> eof }, requestTimeoutMillis = 1_000)
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
        val processes = mutableListOf<ScriptedProcess>()
        val client = CodexAgentClient(
            { _, _ ->
                ScriptedProcess { message, server ->
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
            val events = async { withTimeout(2_000) { client.events.take(3).toList() } }
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
    fun `rejects malformed unknown and orphan messages without deadlock`(): Unit = runBlocking {
        val requestRejected = CountDownLatch(1)
        val process = ScriptedProcess { message, server ->
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
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 1_000)
        try {
            withTimeout(1_000) { client.authenticate() }
            assertTrue(requestRejected.await(1, TimeUnit.SECONDS))
        } finally {
            client.close()
        }

        val malformed = ScriptedProcess { message, server ->
            if (message.method == "initialize") server.sendRaw("not-json")
        }
        val malformedClient = CodexAgentClient({ _, _ -> malformed }, requestTimeoutMillis = 1_000)
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

        val process = ScriptedProcess { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> server.respond(
                    message.id,
                    buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                )
                "turn/interrupt" -> server.respond(message.id, buildJsonObject {})
            }
        }
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 1_000)
        try {
            val session = SessionId("thread-1")
            client.sendPrompt(session, "hello")
            coroutineScope {
                val first = async { client.cancelTurn(session) }
                val second = async { runCatching { client.cancelTurn(session) } }
                first.await()
                assertTrue(second.await().isFailure)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `a terminal notification racing the start response leaves the client usable`(): Unit = runBlocking {
        val turnIds = AtomicInteger()
        val process = ScriptedProcess { message, server ->
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
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 1_000)
        val session = SessionId("thread-1")
        try {
            repeat(20) {
                val completed = async {
                    withTimeout(1_000) {
                        client.events.filterIsInstance<AgentEvent.TurnCompleted>().first()
                    }
                }
                client.sendPrompt(session, "fast turn")
                completed.await()
                assertFailsWith<IllegalStateException> { client.cancelTurn(session) }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `interrupt after provider completion is harmless`(): Unit = runBlocking {
        val process = ScriptedProcess { message, server ->
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
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 1_000)
        try {
            val completed = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.TurnCompleted>().first() }
            }
            client.sendPrompt(SessionId("thread-1"), "hello")
            client.cancelTurn(SessionId("thread-1"))
            assertEquals(SessionId("thread-1"), completed.await().sessionId)
        } finally {
            client.close()
        }
    }

    @Test
    fun `slow event consumers exert bounded backpressure`(): Unit = runBlocking {
        val sentAll = CountDownLatch(1)
        val process = ScriptedProcess { message, server ->
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
                    sentAll.countDown()
                }
            }
        }
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 1_000)
        try {
            client.sendPrompt(SessionId("thread-1"), "hello")
            assertFalse(sentAll.await(100, TimeUnit.MILLISECONDS), "producer was not backpressured")

            val events = withTimeout(5_000) {
                client.events.filterIsInstance<AgentEvent.TextDelta>().take(2_000).toList()
            }
            assertEquals(2_000, events.size)
            assertTrue(sentAll.await(1, TimeUnit.SECONDS))
        } finally {
            client.close()
        }
    }

    @Test
    fun `translates authentication session stream completion and failure events`(): Unit = runBlocking {
        val process = ScriptedProcess { message, server ->
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
                    assertEquals(setOf("type"), params.keys)
                    assertEquals("chatgptDeviceCode", params["type"]!!.jsonPrimitive.content)
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("type", "chatgptDeviceCode")
                            put("loginId", "login-1")
                            put("verificationUrl", "https://auth.openai.com/codex/device")
                            put("userCode", "TEST-CODE")
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
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 1_000)
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
            client.sendPrompt(session, "hello")
            collector.await()

            val required = assertIs<AgentEvent.AuthenticationRequired>(received[0])
            assertEquals("https://auth.openai.com/codex/device", required.verificationUrl)
            assertEquals("TEST-CODE", required.userCode)
            assertIs<AgentEvent.Authenticated>(received[1])
            assertEquals(AgentEvent.SessionOpened(SessionId("thread-1")), received[2])
            assertEquals(AgentEvent.TextDelta(SessionId("thread-1"), "Hello"), received[3])
            assertEquals(AgentEvent.TurnCompleted(SessionId("thread-1")), received[4])
            assertIs<AgentEvent.Failure>(received[5])
        } finally {
            client.close()
        }
    }

    @Test
    fun `failed authentication can be retried without conflicting login state`(): Unit = runBlocking {
        val loginAttempts = AtomicInteger()
        val process = ScriptedProcess { message, server ->
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
                            put("type", "chatgptDeviceCode")
                            put("loginId", loginId)
                            put("verificationUrl", "https://auth.openai.com/codex/device")
                            put("userCode", "TEST-CODE")
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
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 1_000)
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
        val process = ScriptedProcess { message, server ->
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
                            put("type", "chatgptDeviceCode")
                            put("loginId", loginId)
                            put("verificationUrl", "https://auth.openai.com/codex/device")
                            put("userCode", "TEST-CODE")
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
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 1_000)
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
        val process = ScriptedProcess { message, server ->
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
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 50)
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
        val neverStarted = CodexAgentClient({ _, _ -> error("must not launch") })
        assertFailsWith<IllegalArgumentException> {
            neverStarted.sendPrompt(SessionId("thread"), "  \n")
        }
        assertFailsWith<IllegalArgumentException> {
            neverStarted.sendPrompt(SessionId("thread"), "x".repeat(100_001))
        }
        neverStarted.close()

        var observedPrompt: String? = null
        val process = ScriptedProcess { message, server ->
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
        val client = CodexAgentClient({ _, _ -> process }, requestTimeoutMillis = 1_000)
        try {
            val prompt = "Grüezi 👋\n第二行"
            client.sendPrompt(SessionId("thread"), prompt)
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

private class ScriptedProcess(
    handler: (ClientMessage, ScriptedProcess) -> Unit,
) : Process() {
    private val serverInput = PipedInputStream()
    private val clientOutput = PipedOutputStream(serverInput)
    private val clientInput = PipedInputStream()
    private val serverOutput = PipedOutputStream(clientInput)
    private val clientError = PipedInputStream()
    private val serverError = PipedOutputStream(clientError)
    private val alive = AtomicBoolean(true)
    private val exitCode = AtomicInteger()
    private val exited = CountDownLatch(1)
    private val outputClosed = AtomicBoolean(false)
    private val inputClosed = AtomicBoolean(false)
    private val errorClosed = AtomicBoolean(false)
    private val trackedClientOutput = object : FilterOutputStream(clientOutput) {
        override fun close() {
            outputClosed.set(true)
            super.close()
        }
    }
    private val trackedClientInput = object : FilterInputStream(clientInput) {
        override fun close() {
            inputClosed.set(true)
            super.close()
        }
    }
    private val trackedClientError = object : FilterInputStream(clientError) {
        override fun close() {
            errorClosed.set(true)
            super.close()
        }
    }

    init {
        thread(isDaemon = true, name = "fake-app-server") {
            serverInput.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val objectValue = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
                    handler(ClientMessage(objectValue), this)
                }
            }
        }
    }

    fun respond(id: Long?, result: JsonObject) {
        requireNotNull(id)
        sendRaw(buildJsonObject { put("id", id); put("result", result) }.toString())
    }

    fun notify(method: String, params: JsonObject) {
        sendRaw(buildJsonObject { put("method", method); put("params", params) }.toString())
    }

    @Synchronized
    fun sendStderr(value: String) {
        serverError.write("$value\n".toByteArray(StandardCharsets.UTF_8))
        serverError.flush()
    }

    fun exit(code: Int) {
        if (!alive.compareAndSet(true, false)) return
        exitCode.set(code)
        exited.countDown()
    }

    fun closeStdout() {
        serverOutput.close()
    }

    fun allClientStreamsClosed(): Boolean =
        outputClosed.get() && inputClosed.get() && errorClosed.get()

    @Synchronized
    fun sendRaw(value: String) {
        serverOutput.write("$value\n".toByteArray(StandardCharsets.UTF_8))
        serverOutput.flush()
    }

    override fun getOutputStream(): OutputStream = trackedClientOutput

    override fun getInputStream(): InputStream = trackedClientInput

    override fun getErrorStream(): InputStream = trackedClientError

    override fun waitFor(): Int {
        exited.await()
        return exitCode.get()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)

    override fun exitValue(): Int {
        if (alive.get()) throw IllegalThreadStateException("still running")
        return exitCode.get()
    }

    override fun destroy() {
        if (!alive.compareAndSet(true, false)) return
        runCatching { clientOutput.close() }
        runCatching { serverOutput.close() }
        runCatching { serverError.close() }
        runCatching { serverInput.close() }
        runCatching { clientInput.close() }
        runCatching { clientError.close() }
        exited.countDown()
    }

    override fun destroyForcibly(): Process = apply { destroy() }

    override fun isAlive(): Boolean = alive.get()
}

private data class ClientMessage(val objectValue: JsonObject) {
    val id: Long? = objectValue["id"]?.jsonPrimitive?.content?.toLongOrNull()
    val method: String? = objectValue["method"]?.jsonPrimitive?.content
}
