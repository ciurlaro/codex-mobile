package io.github.ciurlaro.codexmobile.app

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ForegroundSessionControllerTest {
    @Test
    fun oneControllerOwnsSessionStreamingCancellationAndBoundedState(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            controller.authenticate()
            await { fake.authenticateCount.get() == 1 }
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.sessionId == SESSION }

            controller.authenticate()
            await { fake.authenticateCount.get() == 2 }
            assertEquals(1, fake.openSessionCount.get())

            controller.submit("bounded response")
            await { fake.promptCount.get() == 1 && controller.state.value.turnActive }
            fake.emit(AgentEvent.TextDelta(SESSION, "x".repeat(300_000)))
            await { controller.state.value.streamedText.endsWith("[Response truncated]") }
            assertTrue(controller.state.value.streamedText.length < 263_000)

            controller.stopAndClose("Stopped by test")
            assertEquals(1, fake.cancelCount.get())
            assertTrue(fake.closed)
            assertTrue(controller.state.value.terminal)
            assertNull(controller.state.value.sessionId)
        } finally {
            controller.close()
        }
    }

    @Test
    fun oneUiOwnerClaimsAToolAndLateApprovalIsRejectedAfterStop(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        val first = tool("first")
        val second = tool("second")
        try {
            fake.emit(first)
            await { controller.state.value.pendingTool == first }
            assertNotNull(controller.claimTool("owner-a", first.call.id))
            assertNull(controller.claimTool("owner-b", first.call.id))

            fake.emit(second)
            await { fake.results.any { it is ToolResult.Rejected && it.callId == second.call.id } }

            controller.stopAndClose("Stopped before approval")
            assertFalse(controller.beginTool("owner-a", first.call.id))
            assertFalse(
                controller.submitToolResult(
                    "owner-a",
                    first,
                    ToolResult.Success(first.call.id, "{}"),
                ),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun detachedUiRejectsUnstartedToolWithoutExecutingIt(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        val event = tool("detached")
        try {
            fake.emit(event)
            await { controller.state.value.pendingTool == event }
            assertNotNull(controller.claimTool("owner", event.call.id))
            controller.releaseOwner("owner", "UI closed")

            await { fake.results.any { it is ToolResult.Rejected && it.callId == event.call.id } }
            assertNull(controller.state.value.pendingTool)
        } finally {
            controller.close()
        }
    }

    @Test
    fun successfulAuthenticationCancellationAllowsRetry(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            controller.authenticate()
            await { fake.authenticateCount.get() == 1 }
            controller.cancelAuthentication()
            await { fake.cancelAuthenticationCount.get() == 1 }

            controller.authenticate()
            await { fake.authenticateCount.get() == 2 }
        } finally {
            controller.close()
        }
    }

    @Test
    fun concurrentSubmissionsStartOneTurnAndNetworkFailureRemainsBounded(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.SessionOpened(SESSION))
            await { controller.state.value.sessionId == SESSION }

            coroutineScope {
                List(32) {
                    async(Dispatchers.Default) { controller.submit("x") }
                }.forEach { it.await() }
            }
            await { fake.promptCount.get() == 1 }
            assertEquals(1, fake.promptCount.get())

            coroutineScope {
                List(32) {
                    async(Dispatchers.Default) { controller.cancelTurn() }
                }.forEach { it.await() }
            }
            await { fake.cancelCount.get() == 1 }
            assertEquals(1, fake.cancelCount.get())
            fake.emit(AgentEvent.TurnCompleted(SESSION))
            await { !controller.state.value.turnActive }

            controller.submit("x")
            await { fake.promptCount.get() == 2 }
            fake.emit(
                AgentEvent.Failure(
                    SESSION,
                    "network_unavailable",
                    "x".repeat(2_000),
                    recoverable = true,
                ),
            )
            await { !controller.state.value.turnActive }
            assertEquals(500, controller.state.value.status.length)
            assertTrue(controller.state.value.attentionRequired)

            controller.submit("x")
            await { fake.promptCount.get() == 3 }
            assertFalse(controller.state.value.attentionRequired)
        } finally {
            controller.close()
        }
    }

    private fun controller(fake: FakeAgentClient) = ForegroundSessionController(
        fake,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private fun tool(id: String) = AgentEvent.ToolRequested(
        SESSION,
        ToolCall(ToolCallId(id), "list_documents", "{}"),
    )

    private class FakeAgentClient : AgentClient {
        private val eventChannel = Channel<AgentEvent>(Channel.UNLIMITED)
        override val events: Flow<AgentEvent> = eventChannel.receiveAsFlow()
        val authenticateCount = AtomicInteger()
        val cancelAuthenticationCount = AtomicInteger()
        val openSessionCount = AtomicInteger()
        val promptCount = AtomicInteger()
        val cancelCount = AtomicInteger()
        val results = CopyOnWriteArrayList<ToolResult>()
        @Volatile var closed = false

        override suspend fun authenticate() {
            authenticateCount.incrementAndGet()
        }

        override suspend fun cancelAuthentication() {
            cancelAuthenticationCount.incrementAndGet()
        }

        override suspend fun openSession(previous: SessionId?): SessionId {
            openSessionCount.incrementAndGet()
            eventChannel.send(AgentEvent.SessionOpened(SESSION))
            return SESSION
        }

        override suspend fun sendPrompt(sessionId: SessionId, prompt: String) {
            promptCount.incrementAndGet()
        }

        override suspend fun cancelTurn(sessionId: SessionId) {
            cancelCount.incrementAndGet()
        }

        override suspend fun submitToolResult(sessionId: SessionId, result: ToolResult) {
            results += result
        }

        override fun close() {
            closed = true
            eventChannel.close()
        }

        suspend fun emit(event: AgentEvent) {
            eventChannel.send(event)
        }
    }

    private companion object {
        val SESSION = SessionId("step05-session")
    }
}
