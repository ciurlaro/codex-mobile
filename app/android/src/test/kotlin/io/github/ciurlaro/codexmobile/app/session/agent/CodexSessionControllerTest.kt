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
import io.github.ciurlaro.codexmobile.core.AgentHookCatalog
import io.github.ciurlaro.codexmobile.core.AgentHookActivity
import io.github.ciurlaro.codexmobile.core.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentPlanProgress
import io.github.ciurlaro.codexmobile.core.AgentPlanStep
import io.github.ciurlaro.codexmobile.core.AgentPlanStepStatus
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

internal class CodexSessionControllerTest : CodexSessionControllerTestBase() {
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
    fun pluginChangeEventsAreRevisionedBeforeAnyCatalogRequest(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.PluginsChanged)
            fake.emit(AgentEvent.PluginsChanged)

            await { controller.state.value.pluginsRevision == 2 }
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

}
