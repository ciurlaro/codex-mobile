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


internal suspend fun CodexAgentClient.sendTurnAction(sessionId: SessionId, request: AgentTurnRequest) {
    flushPluginAvailabilityNotice(sessionId)
    val snapshot = request.copy(
        capabilities = request.capabilities.toSet(),
        invocations = request.invocations.distinctBy(AgentInvocation::key),
    )
    require(
        snapshot.prompt.isNotBlank() || snapshot.capabilities.isNotEmpty() ||
            snapshot.invocations.isNotEmpty(),
    ) {
        "Prompt must not be blank"
    }
    require(snapshot.prompt.length <= MAX_PROMPT_CHARS) { "Prompt is too large" }
    require(snapshot.clientMessageId?.isNotBlank() != false) {
        "Client message ID must not be blank"
    }
    require(snapshot.model?.isNotBlank() != false) { "Model must not be blank" }
    require(snapshot.effort?.isNotBlank() != false) { "Effort must not be blank" }
    require(snapshot.serviceTier?.isNotBlank() != false) { "Service tier must not be blank" }
    require(snapshot.workingDirectory?.startsWith('/') != false) {
        "Working directory must be absolute"
    }
    synchronized(turnStateLock) {
        check(sessionId !in startingTurns && !activeTurns.containsKey(sessionId)) {
            "A turn is already active for this session"
        }
        cancelledTurns -= sessionId
        startingTurns += sessionId
    }
    val previousRuntimeSettings = sessionRuntimeSettings[sessionId]
    sessionRuntimeSettings[sessionId] = SessionRuntimeSettings(
        workspace = snapshot.workingDirectory ?: previousRuntimeSettings?.workspace,
        approvalPreset = snapshot.approvalPreset,
        model = snapshot.model ?: previousRuntimeSettings?.model,
        effort = snapshot.effort ?: previousRuntimeSettings?.effort,
    )
    snapshot.clientMessageId?.takeIf { snapshot.invocations.isNotEmpty() }?.let { clientMessageId ->
        runCatching {
            turnInputMetadataStore.upsert(
                sessionId.value,
                TurnInputMetadata(clientMessageId, snapshot.invocations),
            )
        }
    }

    try {
        val result = connection.request(
            AppServerClientMethods.TurnStart,
            TurnStartParams(
                input = turnInput(snapshot),
                threadId = sessionId.value,
                approvalPolicy = JsonPrimitive(snapshot.approvalPreset.approvalPolicy),
                approvalsReviewer = approvalsReviewer(snapshot.approvalPreset),
                clientUserMessageId = snapshot.clientMessageId,
                cwd = snapshot.workingDirectory,
                effort = snapshot.effort,
                model = snapshot.model,
                collaborationMode = if (snapshot.collaborationMode == AgentCollaborationMode.PLAN) {
                    CollaborationMode(
                        mode = ModeKind.PLAN,
                        settings = Settings(
                            model = snapshot.model ?: previousRuntimeSettings?.model
                                ?: error("Active model is unavailable"),
                            developer_instructions = null,
                            reasoning_effort = "medium",
                        ),
                    )
                } else {
                    null
                },
                serviceTier = snapshot.serviceTier,
                summary = JsonPrimitive("auto"),
            ),
        )
        val turnId = result.turn.id
        synchronized(turnStateLock) {
            startingTurns -= sessionId
            if (terminalDuringStart.remove(sessionId) != turnId) {
                activeTurns[sessionId] = turnId
            }
        }
    } catch (error: Exception) {
        if (previousRuntimeSettings == null) {
            sessionRuntimeSettings -= sessionId
        } else {
            sessionRuntimeSettings[sessionId] = previousRuntimeSettings
        }
        synchronized(turnStateLock) {
            startingTurns -= sessionId
            terminalDuringStart.remove(sessionId)
        }
        throw error
    }
}

internal suspend fun CodexAgentClient.runShellCommandAction(sessionId: SessionId, command: String) {
    val snapshot = command.trim()
    require(snapshot.isNotEmpty()) { "Shell command must not be blank" }
    require(snapshot.length <= MAX_PROMPT_CHARS) { "Shell command is too large" }
    check(sessionId in openedSessions) { "Session is not open" }
    synchronized(turnStateLock) {
        check(sessionId !in startingTurns && !activeTurns.containsKey(sessionId)) {
            "A turn is already active for this session"
        }
        startingTurns += sessionId
    }
    try {
        connection.request(
            AppServerClientMethods.ThreadShellCommand,
            ThreadShellCommandParams(command = snapshot, threadId = sessionId.value),
        )
    } finally {
        synchronized(turnStateLock) {
            startingTurns -= sessionId
            terminalDuringStart.remove(sessionId)
        }
    }
}

internal suspend fun CodexAgentClient.cancelTurnAction(sessionId: SessionId) {
    val turnId = synchronized(turnStateLock) {
        val active = activeTurns[sessionId] ?: error("No active turn for this session")
        check(cancellingTurns.add(sessionId)) { "Turn cancellation is already in progress" }
        cancelledTurns[sessionId] = active
        active
    }
    cancelPendingBuiltInTools(sessionId, turnId, "Built-in tool call was cancelled")
    try {
        try {
            connection.request(
                AppServerClientMethods.TurnInterrupt,
                TurnInterruptParams(threadId = sessionId.value, turnId = turnId),
            )
        } catch (error: AppServerRpcException) {
            if (error.code != -32600L || error.detail != "no active turn to interrupt") throw error
        }
    } finally {
        synchronized(turnStateLock) { cancellingTurns -= sessionId }
    }
}
