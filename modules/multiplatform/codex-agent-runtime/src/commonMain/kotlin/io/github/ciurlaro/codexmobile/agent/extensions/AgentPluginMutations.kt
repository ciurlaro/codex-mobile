package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory
import io.github.ciurlaro.codexmobile.agent.AgentClient
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentConversation
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentElicitationAction
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentFormValue
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentHookActivity
import io.github.ciurlaro.codexmobile.agent.AgentHookCatalog
import io.github.ciurlaro.codexmobile.agent.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.agent.AgentHookTrustStatus
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentMessage
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.agent.AgentPluginDetail
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentPlanProgress
import io.github.ciurlaro.codexmobile.agent.AgentPlanStep
import io.github.ciurlaro.codexmobile.agent.AgentPlanStepStatus
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentServiceTier
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkillChunk
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.SessionId
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalState
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


internal suspend fun CodexAgentClient.readPluginAction(plugin: AgentPluginReference): AgentPluginDetail {
    return try {
        parsePluginDetail(
            pluginRequest(AppServerClientMethods.PluginRead, pluginReadParams(plugin)).plugin,
        ).let { detail ->
            detail.copy(providerManaged = providerHost?.manages(plugin.id) == true)
        }
    } catch (error: AppServerRpcException) {
        throw error.forPlugin(plugin)
    }
}

internal suspend fun CodexAgentClient.addPluginMarketplaceAction(source: String) {
    val marketplace = if (source.startsWith('/')) {
        require(source.length <= 4_096 && '\u0000' !in source) { "Invalid local marketplace path" }
        MarketplaceSource(source)
    } else {
        parseGitHubMarketplaceSource(source)
    }
    pluginRequest(
        AppServerClientMethods.MarketplaceAdd,
        MarketplaceAddParams(marketplace.repository, marketplace.refName, marketplace.sparsePaths),
        retryOnTimeout = true,
    )
    eventsChannel.send(AgentEvent.PluginsChanged)
}

internal suspend fun CodexAgentClient.installPluginAction(plugin: AgentPluginReference): AgentPluginInstallResult {
    val host = providerHost
    val detail = host?.let { readPlugin(plugin) }
    val disposition = host?.install(plugin, detail?.mcpServers.orEmpty().toSet())
        ?: ProviderInstallDisposition.NOT_REQUIRED
    if (disposition == ProviderInstallDisposition.READY) {
        refreshBuiltInTools()
        disableManagedProviderMcp(plugin.id)
    }
    if (disposition == ProviderInstallDisposition.READY && detail?.summary?.installed == true) {
        checkNotNull(host).installCompleted(plugin.id)
        clearPluginCache()
        eventsChannel.send(AgentEvent.PluginsChanged)
        return AgentPluginInstallResult(detail.summary.authPolicy, emptyList())
    }
    val result = try {
        pluginRequest(
            AppServerClientMethods.PluginInstall,
            pluginInstallParams(plugin),
            retryOnTimeout = true,
        )
    } catch (error: AppServerRpcException) {
        throw error.forPlugin(plugin)
    }
    if (disposition == ProviderInstallDisposition.READY) host?.installCompleted(plugin.id)
    clearPluginCache()
    eventsChannel.send(AgentEvent.PluginsChanged)
    return AgentPluginInstallResult(
        authPolicy = enumValueOf(result.authPolicy.name),
        connectorsNeedingAuthentication = result.appsNeedingAuth.map(::parseConnector),
    )
}

internal suspend fun CodexAgentClient.uninstallPluginAction(plugin: AgentPluginReference): AgentPluginRemovalResult {
    require(plugin.id.isNotBlank()) { "Plugin ID must not be blank" }
    val host = providerHost?.takeIf { it.manages(plugin.id) }
    var removalWarning: String? = null
    if (host != null) {
        setPluginEnabled(plugin.id, false)
        val preparation = host.prepareRemoval(plugin.id)
        if (preparation.state == ProviderRemovalState.RETRY_REQUIRED) {
            return AgentPluginRemovalResult(
                completed = false,
                message = preparation.message ?: "Provider cleanup needs retry before uninstall can continue.",
            )
        }
        removalWarning = preparation.message
    }
    pluginRequest(
        AppServerClientMethods.PluginUninstall,
        pluginUninstallParams(plugin),
    )
    if (host != null) {
        host.remove(plugin.id)
        clearPluginCache()
        eventsChannel.send(AgentEvent.PluginsChanged)
        return AgentPluginRemovalResult(
            completed = true,
            message = removalWarning,
        )
    }
    clearPluginCache()
    eventsChannel.send(AgentEvent.PluginsChanged)
    return AgentPluginRemovalResult(completed = true)
}

internal suspend fun CodexAgentClient.setPluginEnabledAction(pluginId: String, enabled: Boolean) {
    require(pluginId.isNotBlank() && '.' !in pluginId) { "Invalid plugin ID" }
    if (builtInPluginEnabled.containsKey(pluginId)) {
        builtInToolGate.withLock {
            pluginRequest(
                AppServerClientMethods.ConfigValueWrite,
                pluginEnablementParams(pluginId, enabled),
                retryOnTimeout = true,
            )
            builtInPluginEnabled[pluginId] = enabled
        }
        notifyOpenSessionsOfPluginAvailability()
    } else {
        pluginRequest(
            AppServerClientMethods.ConfigValueWrite,
            pluginEnablementParams(pluginId, enabled),
            retryOnTimeout = true,
        )
    }
    clearPluginCache()
}

internal suspend fun CodexAgentClient.listConnectorsAction(
    sessionId: SessionId?,
    forceReload: Boolean,
): List<AgentConnector> = requestAllPages(
    AppServerClientMethods.AppList,
    params = { cursor -> AppsListParams(cursor, forceReload, threadId = sessionId?.value) },
    data = AppsListResponse::data,
    nextCursor = AppsListResponse::nextCursor,
    transform = ::parseConnector,
)

internal suspend fun CodexAgentClient.listMcpServersAction(): List<AgentMcpServer> =
    requestAllPages(
        AppServerClientMethods.McpServerStatusList,
        params = { ListMcpServerStatusParams(cursor = it) },
        data = ListMcpServerStatusResponse::data,
        nextCursor = ListMcpServerStatusResponse::nextCursor,
        transform = ::parseMcpServer,
    )
        .filterNot { it.name == INTERNAL_APPS_MCP_SERVER }
