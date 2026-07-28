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


internal suspend fun CodexAgentClient.listHooksAction(workingDirectory: String): AgentHookCatalog {
    validateWorkingDirectory(workingDirectory)
    val entries = connection.request(
        AppServerClientMethods.HooksList,
        HooksListParams(listOf(workingDirectory)),
    ).data
    return AgentHookCatalog(
        hooks = entries.flatMap(HooksListEntry::hooks).distinctBy(HookMetadata::key).map { hook ->
            AgentHook(
                key = hook.key,
                currentHash = hook.currentHash,
                enabled = hook.enabled,
                eventName = hook.eventName.name,
                handlerType = hook.handlerType.name,
                isManaged = hook.isManaged,
                source = hook.source.name,
                sourcePath = hook.sourcePath,
                timeoutSeconds = hook.timeoutSec,
                trustStatus = enumValueOf(hook.trustStatus.name),
                command = hook.command,
                matcher = hook.matcher,
                pluginId = hook.pluginId,
                statusMessage = hook.statusMessage,
            )
        }.sortedBy(AgentHook::key),
        warnings = entries.flatMap(HooksListEntry::warnings).distinct(),
        errors = entries.flatMap(HooksListEntry::errors).map { "${it.path}: ${it.message}" }.distinct(),
    )
}

internal suspend fun CodexAgentClient.setHookEnabledAction(key: String, enabled: Boolean) {
    require(key.isNotBlank()) { "Hook key must not be blank" }
    writeHookState(key) { put("enabled", enabled) }
}

internal suspend fun CodexAgentClient.trustHookAction(key: String, currentHash: String) {
    require(key.isNotBlank()) { "Hook key must not be blank" }
    require(currentHash.isNotBlank()) { "Hook hash must not be blank" }
    writeHookState(key) { put("trusted_hash", currentHash) }
}

internal suspend fun CodexAgentClient.writeHookStateAction(key: String, state: JsonObjectBuilder.() -> Unit) {
    connection.request(
        AppServerClientMethods.ConfigBatchWrite,
        ConfigBatchWriteParams(
            edits = listOf(
                ConfigEdit(
                    keyPath = "hooks.state",
                    mergeStrategy = MergeStrategy.UPSERT,
                    value = buildJsonObject { putJsonObject(key, state) },
                ),
            ),
            reloadUserConfig = true,
        ),
    )
}

internal suspend fun CodexAgentClient.startMcpOauthAction(serverName: String, sessionId: SessionId?): String {
    require(serverName.isNotBlank()) { "MCP server name must not be blank" }
    return connection.request(
        AppServerClientMethods.McpServerOauthLogin,
        McpServerOauthLoginParams(name = serverName, threadId = sessionId?.value),
    ).authorizationUrl.also(::requireSafeAuthUrl)
}

internal suspend fun CodexAgentClient.listSessionsAction(): List<AgentConversationSummary> = requestAllPages(
    AppServerClientMethods.ThreadList,
    params = { cursor ->
        ThreadListParams(
            cursor = cursor,
            sortDirection = SortDirection.DESC,
            sortKey = ThreadSortKey.UPDATED_AT,
        )
    },
    data = ThreadListResponse::data,
    nextCursor = ThreadListResponse::nextCursor,
    transform = { thread ->
        conversationSummary(
            thread,
            shellTranscriptStore.read(thread.id).firstOrNull()?.let { "!${it.command}" },
        )
    },
)

internal suspend fun CodexAgentClient.readSessionAction(sessionId: SessionId): AgentConversation {
    val thread = connection.request(
        AppServerClientMethods.ThreadRead,
        ThreadReadParams(sessionId.value, includeTurns = true),
    ).thread
    check(thread.id == sessionId.value) { "App-server returned another thread" }
    val transcripts = shellTranscriptStore.read(sessionId.value).groupBy(ShellTranscript::turnId)
    val recordedInvocations = turnInputMetadataStore.read(sessionId.value)
    val messages = thread.turns.flatMap { turn ->
        transcripts[turn.id].orEmpty().flatMap(::shellTranscriptMessages) + conversationMessages(
            turn.items.map { item ->
                PROTOCOL_JSON.encodeToJsonElement(ThreadItem.serializer(), item)
            },
            recordedInvocations,
        )
    }
    return AgentConversation(
        conversationSummary(thread, transcripts.values.flatten().firstOrNull()?.let { "!${it.command}" }),
        messages,
    )
}

internal suspend fun CodexAgentClient.renameSessionAction(sessionId: SessionId, name: String) {
    val snapshot = name.trim()
    require(snapshot.isNotEmpty()) { "Conversation name must not be blank" }
    connection.request(
        AppServerClientMethods.ThreadNameSet,
        ThreadSetNameParams(name = snapshot, threadId = sessionId.value),
    )
}

internal suspend fun CodexAgentClient.deleteSessionAction(sessionId: SessionId) {
    connection.request(
        AppServerClientMethods.ThreadDelete,
        ThreadDeleteParams(sessionId.value),
    )
    openedSessions -= sessionId
    sessionRuntimeSettings -= sessionId
    pendingAvailabilityNotices -= sessionId
    threadProviderStates -= sessionId
    threadProviderStateStore.delete(sessionId.value)
    shellTranscriptStore.delete(sessionId.value)
    turnInputMetadataStore.delete(sessionId.value)
    synchronized(turnStateLock) {
        activeTurns -= sessionId
        startingTurns -= sessionId
        terminalDuringStart -= sessionId
        cancellingTurns -= sessionId
        cancelledTurns -= sessionId
    }
}

internal suspend fun <P, R, T, U> CodexAgentClient.requestAllPagesAction(
    method: AppServerMethod<P, R>,
    params: (String?) -> P,
    data: (R) -> List<T>,
    nextCursor: (R) -> String?,
    transform: (T) -> U,
): List<U> {
    val values = mutableListOf<U>()
    val seenCursors = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val page = connection.request(method, params(cursor))
        values += data(page).map(transform)
        cursor = nextCursor(page)
        check(cursor == null || seenCursors.add(cursor)) { "App-server repeated a pagination cursor" }
    } while (cursor != null)
    return values
}
