package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.transport.CodexRuntimeFactory
import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentFormValue
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentHook
import io.github.ciurlaro.codexmobile.core.AgentHookActivity
import io.github.ciurlaro.codexmobile.core.AgentHookCatalog
import io.github.ciurlaro.codexmobile.core.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.core.AgentHookTrustStatus
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.core.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.core.AgentPlanProgress
import io.github.ciurlaro.codexmobile.core.AgentPlanStep
import io.github.ciurlaro.codexmobile.core.AgentPlanStepStatus
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentServiceTier
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkillChunk
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalState
import java.io.File
import java.net.URI
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.KSerializer


internal suspend fun CodexAgentClient.finishTurnAction(sessionId: SessionId, turnId: String?) {
    synchronized(turnStateLock) {
        if (turnId == null || activeTurns[sessionId] == turnId) activeTurns.remove(sessionId)
        if (turnId != null && sessionId in startingTurns) {
            terminalDuringStart[sessionId] = turnId
        }
        cancellingTurns -= sessionId
        if (turnId == null || cancelledTurns[sessionId] == turnId) cancelledTurns -= sessionId
    }
    cancelPendingBuiltInTools(sessionId, turnId, "Built-in tool call is no longer active")
    val removedWork = workItems.entries.removeIf { it.value.first == sessionId }
    if (removedWork) eventsChannel.send(AgentEvent.WorkActivityChanged(sessionId, null))
    if (pendingAvailabilityNotices.containsKey(sessionId)) {
        scope.launch { flushPluginAvailabilityNotice(sessionId) }
    }
}

internal suspend fun CodexAgentClient.updateItemActivityAction(
    threadId: String,
    turnId: String,
    item: ThreadItem,
    started: Boolean,
) {
    val sessionId = SessionId(threadId)
    val itemId = when (item) {
        is ThreadItemCommandExecutionThreadItem -> item.id
        is ThreadItemFileChangeThreadItem -> item.id
        else -> return
    }
    if (
        started && item is ThreadItemCommandExecutionThreadItem &&
        item.source == CommandExecutionSource.USER_SHELL
    ) {
        userShellItems += itemId
        synchronized(turnStateLock) { activeTurns[sessionId] = turnId }
    }
    val activity = when (item) {
        is ThreadItemCommandExecutionThreadItem -> AgentWorkActivity.RUNNING_COMMAND
        is ThreadItemFileChangeThreadItem -> AgentWorkActivity.WRITING_FILES
    }
    if (started) {
        workItems[itemId] = sessionId to activity
        eventsChannel.send(AgentEvent.WorkActivityChanged(sessionId, activity))
    } else if (!started && workItems.remove(itemId) != null) {
        eventsChannel.send(
            AgentEvent.WorkActivityChanged(
                sessionId,
                workItems.values.lastOrNull { it.first == sessionId }?.second,
            ),
        )
    }
}

internal suspend fun CodexAgentClient.completeUserShellItemAction(threadId: String, turnId: String, item: ThreadItem) {
    if (item !is ThreadItemCommandExecutionThreadItem) return
    if (userShellItems.remove(item.id) || item.source == CommandExecutionSource.USER_SHELL) {
        runCatching {
            shellTranscriptStore.upsert(
                threadId,
                ShellTranscript(
                    turnId = turnId,
                    itemId = item.id,
                    command = item.command,
                    output = item.aggregatedOutput.orEmpty().boundedShellTranscript(),
                    exitCode = item.exitCode?.toInt(),
                ),
            )
        }
        eventsChannel.send(
            AgentEvent.ShellCommandCompleted(
                sessionId = SessionId(threadId),
                exitCode = item.exitCode?.toInt(),
            ),
        )
    }
}
