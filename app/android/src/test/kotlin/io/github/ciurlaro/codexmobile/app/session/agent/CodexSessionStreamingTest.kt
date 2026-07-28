package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal class CodexSessionStreamingTest : CodexSessionControllerTestBase() {
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
            fake.emit(AgentEvent.TextDelta(SESSION, "Checking the result", "commentary-1", isCommentary = true))
            fake.emit(AgentEvent.TextDelta(SESSION, "Final answer"))

            await { controller.state.value.streamedText == "Final answer" }
            assertEquals(
                "Inspecting files\n\nComparing results\n\nChecking the result",
                controller.state.value.streamedReasoning,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun plansAndHookActivityStreamIntoTheirOwnStructuredState(): Unit = runBlocking {
        val fake = FakeAgentClient()
        val controller = controller(fake)
        try {
            fake.emit(AgentEvent.Authenticated)
            await { controller.state.value.isAuthenticated }
            assertTrue(controller.submit("plan this"))
            await { controller.state.value.sessionId == SESSION }

            fake.emit(AgentEvent.PlanDelta(SESSION, "## Plan", "plan-1"))
            fake.emit(AgentEvent.PlanDelta(SESSION, "\nDo it", "plan-1"))
            val progress = AgentPlanProgress(
                "Brief plan",
                listOf(AgentPlanStep("Inspect", AgentPlanStepStatus.IN_PROGRESS)),
            )
            fake.emit(AgentEvent.PlanUpdated(SESSION, progress))
            fake.emit(
                AgentEvent.HookActivityChanged(
                    SESSION,
                    AgentHookActivity("hook-1", "PRE_TOOL_USE", "COMMAND", AgentHookRunStatus.RUNNING),
                ),
            )
            fake.emit(
                AgentEvent.HookActivityChanged(
                    SESSION,
                    AgentHookActivity("hook-1", "PRE_TOOL_USE", "COMMAND", AgentHookRunStatus.COMPLETED),
                ),
            )

            await { controller.state.value.hookActivities.singleOrNull()?.status == AgentHookRunStatus.COMPLETED }
            assertEquals("## Plan\nDo it", controller.state.value.streamedPlan)
            assertEquals(progress, controller.state.value.planProgress)
            assertEquals(1, controller.state.value.hookActivities.size)
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

}
