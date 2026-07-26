package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
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

class CodexSessionControllerTest {
    @Test
    fun oneControllerOwnsSessionStreamingCancellationAndBoundedState(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            controller.authenticate()
            await { fake.authenticateCount.get() == 1 }
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.isAuthenticated }
            assertNull(controller.state.value.sessionId)
            assertEquals(0, fake.openSessionCount.get())

            controller.authenticate()
            await { fake.authenticateCount.get() == 2 }
            assertEquals(0, fake.openSessionCount.get())

            controller.submit("bounded response")
            await {
                fake.promptCount.get() == 1 && controller.state.value.isTurnActive &&
                    controller.state.value.sessionId == SESSION
            }
            assertEquals(1, fake.openSessionCount.get())
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
    fun freshChatIsLazyAndRepeatedFreshActionsDoNotCreateEmptyThreads(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.isAuthenticated }

            assertTrue(controller.startNewChat())
            assertTrue(controller.startNewChat())
            assertEquals(0, fake.openSessionCount.get())

            assertTrue(controller.submit("first"))
            await { fake.promptCount.get() == 1 }
            fake.emit(AgentEvent.TurnCompleted(SESSION))
            await { !controller.state.value.isTurnActive }

            assertTrue(controller.startNewChat())
            assertTrue(controller.startNewChat())
            assertNull(controller.state.value.sessionId)
            assertEquals(1, fake.openSessionCount.get())

            assertTrue(controller.submit("second"))
            await { fake.promptCount.get() == 2 }
            assertEquals(2, fake.openSessionCount.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun pluginChangesWaitForTheCurrentReply(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.isAuthenticated }
            assertTrue(controller.submit("keep responding"))
            await { controller.state.value.isTurnActive }

            val error = assertFailsWith<IllegalStateException> {
                controller.setPluginEnabled("documents@codex-mobile", true)
            }

            assertEquals("Wait for the current reply before changing plugins", error.message)
            assertNull(controller.state.value.externalOperation)
        } finally {
            controller.close()
        }
    }

    @Test
    fun typedTurnSnapshotsModelEffortAndCapabilityBeforeGeneration(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.isAuthenticated }
            val first = AgentTurnRequest(
                prompt = "current request",
                clientMessageId = "message-1",
                model = "model-a",
                effort = "high",
                capabilities = setOf(AgentCapability.WEB_SEARCH),
            )
            assertTrue(controller.submit(first))
            await { fake.requests.size == 1 }

            assertEquals(first, fake.requests.single())
            assertTrue(controller.state.value.isTurnActive)
        } finally {
            controller.close()
        }
    }

    @Test
    fun shellCommandStreamsRawOutputAndKeepsItsWorkspace(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.isAuthenticated }
            val settings = AgentRuntimeSettings(workingDirectory = "/storage/emulated/0/Documents")

            assertTrue(controller.submitShell("printf 'a\\nb\\n'", settings))
            await { fake.shellCommands.size == 1 }
            assertEquals(settings, fake.openSettings.single())
            assertEquals("printf 'a\\nb\\n'", fake.shellCommands.single())

            fake.emit(AgentEvent.ShellOutputDelta(SESSION, "a\nb\n"))
            fake.emit(AgentEvent.ShellCommandCompleted(SESSION, 0))
            fake.emit(AgentEvent.TurnCompleted(SESSION))
            await { !controller.state.value.isTurnActive }
            assertEquals("a\nb\n", controller.state.value.streamedText)
            assertEquals(0, controller.state.value.shellExitCode)
        } finally {
            controller.close()
        }
    }

    @Test
    fun reasoningSummariesStreamSeparatelyAndPreserveParts(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.isAuthenticated }
            assertTrue(controller.submit("explain"))
            await { controller.state.value.sessionId == SESSION }

            fake.emit(AgentEvent.ReasoningSummaryDelta(SESSION, "Inspecting", "reasoning-1", 0))
            fake.emit(AgentEvent.ReasoningSummaryDelta(SESSION, " files", "reasoning-1", 0))
            fake.emit(AgentEvent.ReasoningSummaryDelta(SESSION, "Comparing results", "reasoning-1", 1))
            fake.emit(AgentEvent.TextDelta(SESSION, "Final answer"))

            await { controller.state.value.streamedText == "Final answer" }
            assertEquals("Inspecting files\n\nComparing results", controller.state.value.streamedReasoning)
        } finally {
            controller.close()
        }
    }

    @Test
    fun stopRequestedDuringLazySessionCreationCancelsTheStartedTurn(): Unit = runBlocking {
        val fake = FakeAgentClient().apply { blockOpenSession = true }
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.isAuthenticated }

            assertTrue(controller.submit("stop this"))
            fake.openSessionStarted.await()
            assertNull(controller.state.value.sessionId)
            controller.cancelTurn()
            assertEquals("Cancelling…", controller.state.value.statusMessage)
            assertEquals(0, fake.cancelCount.get())

            fake.finishOpenSession.complete(Unit)
            await { fake.promptCount.get() == 1 && fake.cancelCount.get() == 1 }
            assertEquals(1, fake.cancelCount.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun stopAfterSessionOpenWaitsForTurnStartBeforeInterrupting(): Unit = runBlocking {
        val fake = FakeAgentClient().apply { blockSendTurn = true }
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.isAuthenticated }

            assertTrue(controller.submit("stop after open"))
            fake.sendTurnStarted.await()
            await { controller.state.value.sessionId == SESSION }
            controller.cancelTurn()
            assertEquals("Cancelling…", controller.state.value.statusMessage)
            assertEquals(0, fake.cancelCount.get())

            fake.finishSendTurn.complete(Unit)
            await { fake.cancelCount.get() == 1 }
            assertEquals(1, fake.cancelCount.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun conversationSelectionUsesRuntimeHistoryWithoutAnotherStore(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.isAuthenticated }

            assertEquals(listOf(SUMMARY), controller.listConversations())
            assertTrue(controller.openConversation(SESSION))
            await { controller.state.value.sessionId == SESSION }
            assertEquals(CONVERSATION, controller.readConversation(SESSION))
            controller.renameConversation(SESSION, "Renamed")
            controller.deleteConversation(SESSION)
            assertEquals(listOf(SESSION to "Renamed"), fake.renamedSessions)
            assertEquals(listOf(SESSION), fake.deletedSessions)
        } finally {
            controller.close()
        }
    }

    @Test
    fun signOutClosesTheOwnerAndClearsVisibleSessionState(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        fake.emit(AgentEvent.SessionOpened(SESSION))
        await { controller.state.value.sessionId == SESSION }

        assertTrue(controller.stopAndClose("Signed out", signOut = true))

        assertEquals(1, fake.signOutCount.get())
        assertTrue(fake.closed)
        assertEquals("Signed out", controller.state.value.statusMessage)
        assertNull(controller.state.value.sessionId)
        assertTrue(controller.state.value.terminal)
    }

    @Test
    fun signOutDoesNotReleaseTheServiceBeforeLogoutFinishes(): Unit = runBlocking {
        val fake = FakeAgentClient().apply { blockSignOut = true }
        val controller = controller(fake)
        val result = async { controller.stopAndClose("Signed out", signOut = true) }

        fake.signOutStarted.await()
        assertFalse(controller.state.value.terminal)
        fake.finishSignOut.complete(Unit)

        assertTrue(result.await())
        assertTrue(controller.state.value.terminal)
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
            await { !controller.state.value.isTurnActive }

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
            await { !controller.state.value.isTurnActive }
            assertEquals(500, controller.state.value.statusMessage.length)
            assertTrue(controller.state.value.attentionRequired)
            assertEquals("network_unavailable", controller.state.value.diagnosticCode)

            controller.submit("x")
            await { fake.promptCount.get() == 3 }
            assertFalse(controller.state.value.attentionRequired)
        } finally {
            controller.close()
        }
    }

    private fun controller(fake: FakeAgentClient) = CodexSessionController(
        fake,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private class FakeAgentClient : AgentClient {
        private val eventChannel = Channel<AgentEvent>(Channel.UNLIMITED)
        override val events: Flow<AgentEvent> = eventChannel.receiveAsFlow()
        val authenticateCount = AtomicInteger()
        val cancelAuthenticationCount = AtomicInteger()
        val signOutCount = AtomicInteger()
        val signOutStarted = CompletableDeferred<Unit>()
        val finishSignOut = CompletableDeferred<Unit>()
        var blockSignOut = false
        val openSessionCount = AtomicInteger()
        val openSettings = CopyOnWriteArrayList<AgentRuntimeSettings>()
        val openSessionStarted = CompletableDeferred<Unit>()
        val finishOpenSession = CompletableDeferred<Unit>()
        var blockOpenSession = false
        val promptCount = AtomicInteger()
        val sendTurnStarted = CompletableDeferred<Unit>()
        val finishSendTurn = CompletableDeferred<Unit>()
        var blockSendTurn = false
        val cancelCount = AtomicInteger()
        val requests = CopyOnWriteArrayList<AgentTurnRequest>()
        val shellCommands = CopyOnWriteArrayList<String>()
        val renamedSessions = CopyOnWriteArrayList<Pair<SessionId, String>>()
        val deletedSessions = CopyOnWriteArrayList<SessionId>()
        @Volatile var closed = false

        override suspend fun authenticate() {
            authenticateCount.incrementAndGet()
        }

        override suspend fun cancelAuthentication() {
            cancelAuthenticationCount.incrementAndGet()
        }

        override suspend fun signOut() {
            signOutCount.incrementAndGet()
            signOutStarted.complete(Unit)
            if (blockSignOut) finishSignOut.await()
        }

        override suspend fun openSession(previous: SessionId?, settings: AgentRuntimeSettings): SessionId {
            openSessionCount.incrementAndGet()
            openSettings += settings
            openSessionStarted.complete(Unit)
            if (blockOpenSession) finishOpenSession.await()
            eventChannel.send(AgentEvent.SessionOpened(SESSION))
            return SESSION
        }

        override suspend fun sendTurn(sessionId: SessionId, request: AgentTurnRequest) {
            requests += request
            promptCount.incrementAndGet()
            sendTurnStarted.complete(Unit)
            if (blockSendTurn) finishSendTurn.await()
        }

        override suspend fun runShellCommand(sessionId: SessionId, command: String) {
            shellCommands += command
        }

        override suspend fun listModels(): List<AgentModel> = emptyList()

        override suspend fun listSkills(workingDirectory: String, forceReload: Boolean) =
            AgentSkillCatalog(emptyList())

        override suspend fun readSkill(path: String, offset: Long) = error("unused")

        override suspend fun setSkillEnabled(path: String, enabled: Boolean) = Unit

        override suspend fun listInstalledPlugins(workingDirectory: String) = AgentPluginCatalog(emptyList())

        override suspend fun listAvailablePlugins(workingDirectory: String, forceRefresh: Boolean) =
            AgentPluginCatalog(emptyList())

        override suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail = error("unused")

        override suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult = error("unused")

        override suspend fun uninstallPlugin(plugin: AgentPluginReference) = AgentPluginRemovalResult(completed = true)

        override suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) = Unit

        override suspend fun listConnectors(sessionId: SessionId?, forceReload: Boolean): List<AgentConnector> =
            emptyList()

        override suspend fun listMcpServers(): List<AgentMcpServer> = emptyList()

        override suspend fun startMcpOauth(serverName: String, sessionId: SessionId?): String = error("unused")

        override suspend fun listSessions(): List<AgentConversationSummary> = listOf(SUMMARY)

        override suspend fun readSession(sessionId: SessionId): AgentConversation = CONVERSATION

        override suspend fun renameSession(sessionId: SessionId, name: String) {
            renamedSessions += sessionId to name
        }

        override suspend fun deleteSession(sessionId: SessionId) {
            deletedSessions += sessionId
        }

        override suspend fun cancelTurn(sessionId: SessionId) {
            cancelCount.incrementAndGet()
        }

        override suspend fun resolveApproval(
            requestId: String,
            decision: AgentApprovalDecision,
        ) = Unit

        override suspend fun resolveElicitation(
            requestId: String,
            response: AgentElicitationResponse,
        ) = Unit

        override fun close() {
            closed = true
            eventChannel.close()
        }

        suspend fun emit(event: AgentEvent) {
            eventChannel.send(event)
        }
    }

    private companion object {
        val SESSION = SessionId("session")
        val SUMMARY = AgentConversationSummary(SESSION, "Synthetic history", 1L)
        val CONVERSATION = AgentConversation(
            SUMMARY,
            listOf(AgentMessage("message", null, AgentMessageRole.CODEX, "Synthetic response")),
        )
    }
}

private fun CodexSessionController.submit(prompt: String): Boolean =
    submit(AgentTurnRequest(prompt = prompt))
