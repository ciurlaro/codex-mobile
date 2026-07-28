package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.core.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout

internal abstract class CodexSessionControllerTestBase {
    protected fun controller(fake: FakeAgentClient) = CodexSessionController(
        fake,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    protected suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    protected class FakeAgentClient : AgentClient {
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

        override suspend fun listInstalledPlugins(workingDirectory: String?, forceRefresh: Boolean) =
            AgentPluginCatalog(emptyList())

        override suspend fun listAvailablePlugins(workingDirectory: String?, forceRefresh: Boolean) =
            AgentPluginCatalog(emptyList())

        override suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail = error("unused")

        override suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult = error("unused")

        override suspend fun uninstallPlugin(plugin: AgentPluginReference) = AgentPluginRemovalResult(completed = true)

        override suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) = Unit

        override suspend fun listConnectors(sessionId: SessionId?, forceReload: Boolean): List<AgentConnector> =
            emptyList()

        override suspend fun listMcpServers(): List<AgentMcpServer> = emptyList()

        override suspend fun listHooks(workingDirectory: String): AgentHookCatalog =
            AgentHookCatalog(emptyList())

        override suspend fun setHookEnabled(key: String, enabled: Boolean) = Unit

        override suspend fun trustHook(key: String, currentHash: String) = Unit

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

    protected companion object {
        val SESSION = SessionId("session")
        val SUMMARY = AgentConversationSummary(SESSION, "Synthetic history", 1L)
        val CONVERSATION = AgentConversation(
            SUMMARY,
            listOf(AgentMessage("message", null, AgentMessageRole.CODEX, "Synthetic response")),
        )
    }
}

internal fun CodexSessionController.submit(prompt: String): Boolean =
    submit(AgentTurnRequest(prompt = prompt))
