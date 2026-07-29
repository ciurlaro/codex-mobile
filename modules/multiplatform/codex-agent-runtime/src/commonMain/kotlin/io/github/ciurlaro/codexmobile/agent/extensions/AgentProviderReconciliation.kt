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


internal suspend fun CodexAgentClient.disableManagedProviderMcpAction(pluginId: String) {
    providerHost?.mcpServerNames(pluginId).orEmpty().forEach { serverName ->
        pluginRequest(
            AppServerClientMethods.ConfigValueWrite,
            ConfigValueWriteParams(
                keyPath = "mcp_servers.$serverName",
                value = JsonNull,
                mergeStrategy = MergeStrategy.UPSERT,
            ),
            retryOnTimeout = true,
        )
        pluginRequest(
            AppServerClientMethods.ConfigValueWrite,
            ConfigValueWriteParams(
                keyPath = "plugins.$pluginId.mcp_servers.$serverName.enabled",
                value = JsonPrimitive(false),
                mergeStrategy = MergeStrategy.UPSERT,
            ),
            retryOnTimeout = true,
        )
    }
}

internal fun CodexAgentClient.refreshBuiltInToolsAction() {
    val definitions = builtInToolDispatcher?.definitions().orEmpty()
    builtInToolDefinitions = definitions
    builtInToolsByName = definitions.associateBy(BuiltInToolDefinition::name)
    definitions.map(BuiltInToolDefinition::pluginId).forEach { builtInPluginEnabled.putIfAbsent(it, true) }
}

internal suspend fun CodexAgentClient.completePendingProviderInstallsAction() {
    val host = providerHost ?: return
    refreshBuiltInTools()
    host.pendingInstalls().forEach { plugin ->
        val detail = readPlugin(plugin)
        check(detail.mcpServers.toSet() == host.mcpServerNames(plugin.id)) {
            "Provider MCP configuration changed before activation"
        }
        disableManagedProviderMcp(plugin.id)
        if (!detail.summary.installed) {
            pluginRequest(
                AppServerClientMethods.PluginInstall,
                pluginInstallParams(plugin),
                retryOnTimeout = true,
            )
        }
        host.installCompleted(plugin.id)
    }
}

internal suspend fun CodexAgentClient.completePreparedProviderRemovalsAction(catalog: AgentPluginCatalog) {
    val host = providerHost ?: return
    val installed = catalog.plugins.filter { it.installed }.map { it.reference.id }.toSet()
    host.preparedRemovals().forEach { plugin ->
        if (plugin.id in installed) {
            pluginRequest(
                AppServerClientMethods.PluginUninstall,
                pluginUninstallParams(plugin),
            )
        }
        host.remove(plugin.id)
    }
}

internal fun CodexAgentClient.reconcileProvidersInBackgroundAction(catalog: AgentPluginCatalog? = null) {
    val host = providerHost ?: return
    if (host.pendingInstalls().isEmpty() && (catalog == null || host.preparedRemovals().isEmpty())) return
    if (!pendingProviderCompletionRunning.compareAndSet(false, true)) return
    scope.launch {
        try {
            completePendingProviderInstalls()
            if (catalog != null) completePreparedProviderRemovals(catalog)
            eventsChannel.send(AgentEvent.PluginsChanged)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            eventsChannel.send(
                AgentEvent.Failure(null, "provider_install_recovery_failed", error.visibleMessage(), true),
            )
        } finally {
            pendingProviderCompletionRunning.store(false)
        }
    }
}

internal fun CodexAgentClient.parseGitHubMarketplaceSourceAction(value: String): MarketplaceSource {
    val source = value.trim()
    require(source.startsWith("https://", ignoreCase = true)) {
        "Use a public https://github.com repository URL"
    }
    val remainder = source.substringAfter("://")
    val host = remainder.substringBefore('/')
    require(host.equals("github.com", ignoreCase = true) && source.none { it in "?#@\\" }) {
        "Invalid GitHub repository URL"
    }
    val segments = remainder.substringAfter('/', "").trim('/').split('/').filter(String::isNotBlank)
    require(segments.size >= 2 && segments.take(2).all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
        "Use a GitHub repository or tree URL"
    }
    val repository = segments[1].removeSuffix(".git")
    require(repository.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))) { "Invalid repository name" }
    val repositoryUrl = "https://github.com/${segments[0]}/$repository.git"
    if (segments.size == 2) return MarketplaceSource(repositoryUrl)
    require(segments.size >= 4 && segments[2] == "tree") {
        "Use a GitHub repository or tree URL"
    }
    val refName = segments[3]
    require(refName.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid Git reference" }
    val sparsePaths = segments.drop(4).takeIf { it.isNotEmpty() }?.let { path ->
        require(path.all { it.matches(Regex("[A-Za-z0-9._-]+")) }) { "Invalid repository path" }
        listOf(path.joinToString("/"))
    }
    return MarketplaceSource(repositoryUrl, refName, sparsePaths)
}
