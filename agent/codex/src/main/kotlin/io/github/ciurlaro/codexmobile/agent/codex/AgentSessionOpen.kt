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


internal suspend fun CodexAgentClient.openSessionAction(previous: SessionId?, settings: AgentRuntimeSettings): SessionId {
    completePendingProviderInstalls()
    if (builtInToolDispatcher != null) {
        connection.ensureStarted()
        refreshBuiltInPluginEnablement(settings.workingDirectory ?: "/")
    }
    val developerInstructions =
        "Answer conversationally using Markdown. The shell starts in the user's selected Android " +
            "workspace and may use ordinary shell commands to inspect and modify files. Use enabled " +
            "plugin tools through their advertised typed contracts. Use the " +
            "built-in web search tool only when the user input contains the structured " +
            "'${AgentCapability.WEB_SEARCH.promptLabel}' prompt tag."
    val config = buildJsonObject {
        put("web_search", "live")
        putJsonObject("tools") {
            putJsonObject("experimental_request_user_input") { put("enabled", true) }
        }
        putJsonObject("features") {
            put("shell_tool", true)
            put("code_mode", false)
            put("multi_agent", false)
            put("apps", true)
            put("enable_mcp_apps", true)
            put("plugins", true)
            put("image_generation", false)
            put("goals", false)
            put("hooks", true)
            put("skill_mcp_dependency_install", false)
            put("workspace_dependencies", false)
            put("standalone_web_search", false)
        }
        putJsonObject("shell_environment_policy") {
            put("inherit", "all")
            put(
                "exclude",
                buildJsonArray {
                    listOf(
                        "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
                        "http_proxy", "https_proxy", "all_proxy", "no_proxy",
                    ).forEach { add(JsonPrimitive(it)) }
                },
            )
        }
    }
    val opened = if (previous == null) {
        val result = connection.request(
            AppServerClientMethods.ThreadStart,
            ThreadStartParams(
                approvalPolicy = JsonPrimitive(settings.approvalPreset.approvalPolicy),
                approvalsReviewer = approvalsReviewer(settings.approvalPreset),
                config = config,
                cwd = settings.workingDirectory,
                developerInstructions = developerInstructions,
                ephemeral = false,
                sandbox = SandboxMode.DANGER_FULL_ACCESS,
                serviceTier = settings.serviceTier,
                dynamicTools = builtInToolDispatcher?.let {
                    builtInDynamicTools(
                        builtInPluginEnabled.filterValues { it }.keys,
                        builtInToolDefinitions,
                    )
                },
            ),
        )
        AgentEvent.SessionOpened(
            sessionId = SessionId(result.thread.id),
            model = result.model,
            effort = result.reasoningEffort,
            serviceTier = result.serviceTier,
        )
    } else {
        val result = connection.request(
            AppServerClientMethods.ThreadResume,
            ThreadResumeParams(
                threadId = previous.value,
                approvalPolicy = JsonPrimitive(settings.approvalPreset.approvalPolicy),
                approvalsReviewer = approvalsReviewer(settings.approvalPreset),
                config = config,
                cwd = settings.workingDirectory,
                developerInstructions = developerInstructions,
                sandbox = SandboxMode.DANGER_FULL_ACCESS,
                serviceTier = settings.serviceTier,
            ),
        )
        AgentEvent.SessionOpened(
            sessionId = SessionId(result.thread.id),
            model = result.model,
            effort = null,
            serviceTier = settings.serviceTier,
        )
    }
    val sessionId = opened.sessionId
    openedSessions += sessionId
    sessionRuntimeSettings[sessionId] = SessionRuntimeSettings(
        workspace = settings.workingDirectory,
        approvalPreset = settings.approvalPreset,
        model = opened.model,
        effort = opened.effort,
    )
    eventsChannel.send(opened)
    if (previous == null) {
        val original = builtInPluginEnabled.filterValues { it }.keys.toSet()
        val state = ThreadProviderState(original, original.associateWith { true })
        threadProviderStates[sessionId] = state
        runCatching { threadProviderStateStore.write(sessionId.value, state) }
    } else {
        val state = threadProviderStateStore.read(sessionId.value)
            ?: ThreadProviderState(emptySet(), emptyMap())
        threadProviderStates[sessionId] = state
        notifySessionOfPluginAvailability(sessionId)
    }
    return sessionId
}
