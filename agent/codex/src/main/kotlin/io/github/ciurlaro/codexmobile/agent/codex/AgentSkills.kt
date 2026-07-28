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


internal suspend fun CodexAgentClient.listModelsAction(): List<AgentModel> =
    requestAllPages(
        AppServerClientMethods.ModelList,
        params = { ModelListParams(cursor = it) },
        data = ModelListResponse::data,
        nextCursor = ModelListResponse::nextCursor,
    ) { item ->
    val serviceTiers = item.serviceTiers.orEmpty().map { tier ->
            AgentServiceTier(tier.id, tier.name, tier.description)
        }.distinctBy(AgentServiceTier::id)
    AgentModel(
        id = item.model,
        displayName = item.displayName,
        description = item.description,
        supportedEfforts = item.supportedReasoningEfforts.map { it.reasoningEffort },
        defaultEffort = item.defaultReasoningEffort,
        isDefault = item.isDefault,
        serviceTiers = serviceTiers,
        defaultServiceTier = item.defaultServiceTier,
    )
}

internal suspend fun CodexAgentClient.listSkillsAction(
    workingDirectory: String,
    forceReload: Boolean,
): AgentSkillCatalog {
    require(workingDirectory.startsWith('/')) { "Working directory must be absolute" }
    val result = connection.request(
        AppServerClientMethods.SkillsList,
        SkillsListParams(cwds = listOf(workingDirectory), forceReload = forceReload),
    )
    val entries = result.data
    return AgentSkillCatalog(
        skills = entries.flatMap { it.skills }.map(::parseSkill)
            .distinctBy { it.path },
        errors = entries.flatMap { it.errors }.map { "${it.path}: ${it.message}" },
    ).also { catalog ->
        knownSkillPaths.clear()
        catalog.skills.mapTo(knownSkillPaths, io.github.ciurlaro.codexmobile.core.AgentSkill::path)
    }
}

internal suspend fun CodexAgentClient.readSkillAction(path: String, offset: Long): AgentSkillChunk = withContext(Dispatchers.IO) {
    require(path in knownSkillPaths) { "Skill was not returned by skills/list" }
    require(offset >= 0) { "Offset must not be negative" }
    val file = File(path)
    require(file.isFile && file.canRead()) { "Skill source is not readable" }
    RandomAccessFile(file, "r").use { source ->
        val total = source.length()
        require(offset <= total) { "Offset exceeds skill source size" }
        source.seek(offset)
        val bytes = ByteArray(SKILL_CHUNK_BYTES)
        val count = source.read(bytes).coerceAtLeast(0)
        val complete = if (offset + count < total) completeUtf8Length(bytes, count) else count
        val next = (offset + complete).takeIf { it < total }
        AgentSkillChunk(
            content = String(bytes, 0, complete, StandardCharsets.UTF_8),
            nextOffset = next,
            totalBytes = total,
        )
    }
}

internal suspend fun CodexAgentClient.setSkillEnabledAction(path: String, enabled: Boolean) {
    require(path.startsWith('/')) { "Skill path must be absolute" }
    connection.request(
        AppServerClientMethods.SkillsConfigWrite,
        SkillsConfigWriteParams(path = path, enabled = enabled),
    )
}
