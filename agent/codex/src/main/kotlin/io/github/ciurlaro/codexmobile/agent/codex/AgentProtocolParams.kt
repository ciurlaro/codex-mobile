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


internal fun CodexAgentClient.shellTranscriptMessagesAction(transcript: ShellTranscript): List<AgentMessage> = listOf(
    AgentMessage(
        id = "shell-user-${transcript.itemId}",
        clientId = null,
        role = AgentMessageRole.USER,
        text = "!${transcript.command}",
        shellCommand = transcript.command,
    ),
    AgentMessage(
        id = transcript.itemId,
        clientId = null,
        role = AgentMessageRole.CODEX,
        text = transcript.output,
        shellCommand = transcript.command,
        exitCode = transcript.exitCode,
    ),
)

internal fun CodexAgentClient.pluginReadParamsAction(plugin: AgentPluginReference) = PluginReadParams(
    pluginName = plugin.appServerPluginName(),
    marketplacePath = plugin.marketplacePath,
    remoteMarketplaceName = plugin.marketplaceName.takeIf { plugin.marketplacePath == null },
)

internal fun CodexAgentClient.pluginInstallParamsAction(plugin: AgentPluginReference) = PluginInstallParams(
    pluginName = plugin.appServerPluginName(),
    marketplacePath = plugin.marketplacePath,
    remoteMarketplaceName = plugin.marketplaceName.takeIf { plugin.marketplacePath == null },
)

internal fun CodexAgentClient.pluginUninstallParamsAction(plugin: AgentPluginReference) = PluginUninstallParams(
    pluginId = if (plugin.marketplacePath == null) plugin.appServerPluginName() else plugin.id,
)

internal fun CodexAgentClient.pluginEnablementParamsAction(pluginId: String, enabled: Boolean) = ConfigValueWriteParams(
    keyPath = "plugins.$pluginId.enabled",
    value = JsonPrimitive(enabled),
    mergeStrategy = MergeStrategy.UPSERT,
)

internal fun CodexAgentClient.approvalsReviewerAction(preset: AgentApprovalPreset) = when (preset) {
    AgentApprovalPreset.AUTO_REVIEW -> ApprovalsReviewer.AUTO_REVIEW
    else -> ApprovalsReviewer.USER
}

internal fun CodexAgentClient.elicitationResponseAction(response: AgentElicitationResponse): McpServerElicitationRequestResponse {
    val content = if (response.action == AgentElicitationAction.ACCEPT) {
        buildJsonObject {
            response.content.forEach { (name, value) ->
                put(
                    name,
                    when (value) {
                        is AgentFormValue.Text -> JsonPrimitive(value.value)
                        is AgentFormValue.Number -> JsonPrimitive(value.value)
                        is AgentFormValue.BooleanValue -> JsonPrimitive(value.value)
                        is AgentFormValue.TextList -> JsonArray(value.value.map(::JsonPrimitive))
                    },
                )
            }
        }
    } else {
        null
    }
    return McpServerElicitationRequestResponse(
        action = when (response.action) {
            AgentElicitationAction.ACCEPT -> McpServerElicitationAction.ACCEPT
            AgentElicitationAction.DECLINE -> McpServerElicitationAction.DECLINE
            AgentElicitationAction.CANCEL -> McpServerElicitationAction.CANCEL
        },
        content = content,
    )
}
