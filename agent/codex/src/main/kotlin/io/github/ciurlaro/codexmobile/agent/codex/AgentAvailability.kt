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


internal suspend fun CodexAgentClient.notifyOpenSessionsOfPluginAvailabilityAction() {
    openedSessions.forEach { notifySessionOfPluginAvailability(it) }
}

internal suspend fun CodexAgentClient.notifySessionOfPluginAvailabilityAction(sessionId: SessionId) {
    val state = threadProviderStates[sessionId] ?: return
    val availability = state.originalPluginIds.associateWith { builtInPluginEnabled[it] == true }
    val effectiveAvailability = pendingAvailabilityNotices[sessionId]?.availability ?: state.lastAvailability
    if (availability == effectiveAvailability) return
    sendPluginAvailabilityNotice(
        sessionId,
        PendingAvailabilityNotice(pluginAvailabilityNotice(availability), availability),
    )
}

internal fun CodexAgentClient.pluginAvailabilityNoticeAction(availability: Map<String, Boolean>): String = buildString {
    append("Codex Mobile plugin availability changed. Current state: ")
    append(
        availability.entries.joinToString { (pluginId, enabled) ->
            "$pluginId=${if (enabled) "enabled" else "unavailable"}"
        },
    )
    append(". Use only enabled plugin tools that are registered in this thread. ")
    append("Do not rely on unavailable plugin skill instructions; continue the session normally.")
}

internal suspend fun CodexAgentClient.sendPluginAvailabilityNoticeAction(sessionId: SessionId, pending: PendingAvailabilityNotice) {
    val notice = pending.text
    pendingAvailabilityNotices[sessionId] = pending
    val activeTurn = synchronized(turnStateLock) { activeTurns[sessionId] }
    val delivered = runCatching {
        if (activeTurn == null) {
            connection.request(
                AppServerClientMethods.ThreadInjectItems,
                ThreadInjectItemsParams(
                    items = listOf(
                        PROTOCOL_JSON.encodeToJsonElement(
                            ResponseItem.serializer(),
                            ResponseItemMessageResponseItem(
                                content = listOf(ContentItemInputTextContentItem(notice)),
                                role = "developer",
                            ),
                        ),
                    ),
                    threadId = sessionId.value,
                ),
            )
        } else {
            connection.request(
                AppServerClientMethods.TurnSteer,
                TurnSteerParams(
                    expectedTurnId = activeTurn,
                    input = listOf(UserInputTextUserInput(notice)),
                    threadId = sessionId.value,
                    clientUserMessageId = "$AVAILABILITY_MESSAGE_PREFIX:${System.nanoTime()}",
                    additionalContext = mapOf(
                        "codex-mobile.plugin-availability" to AdditionalContextEntry(
                            kind = AdditionalContextKind.APPLICATION,
                            value = notice,
                        ),
                    ),
                ),
            )
        }
    }.isSuccess
    if (delivered && pendingAvailabilityNotices.remove(sessionId, pending)) {
        val state = threadProviderStates[sessionId] ?: return
        val updated = state.copy(lastAvailability = pending.availability)
        threadProviderStates[sessionId] = updated
        runCatching { threadProviderStateStore.write(sessionId.value, updated) }
    }
}

internal suspend fun CodexAgentClient.flushPluginAvailabilityNoticeAction(sessionId: SessionId) {
    pendingAvailabilityNotices[sessionId]?.let { sendPluginAvailabilityNotice(sessionId, it) }
}

internal suspend fun CodexAgentClient.refreshBuiltInPluginEnablementAction(workingDirectory: String) {
    if (builtInEnablementLoaded.get()) return
    builtInToolGate.withLock {
        if (builtInEnablementLoaded.get()) return
        runCatching {
            val result = pluginRequest(
                AppServerClientMethods.PluginInstalled,
                PluginInstalledParams(cwds = listOf(workingDirectory)),
            )
            applyBuiltInPluginEnablement(
                AgentPluginCatalog(parsePluginMarketplaces(result.marketplaces), emptyList()),
            )
        }.onFailure {
            builtInPluginEnabled.keys.forEach { builtInPluginEnabled[it] = false }
        }
        builtInEnablementLoaded.set(true)
    }
}

internal fun CodexAgentClient.applyBuiltInPluginEnablementAction(catalog: AgentPluginCatalog) {
    builtInPluginEnabled.keys.forEach { pluginId ->
        val plugin = catalog.plugins.singleOrNull { it.reference.id == pluginId }
        builtInPluginEnabled[pluginId] = plugin?.let { it.installed && it.enabled } == true
    }
}
