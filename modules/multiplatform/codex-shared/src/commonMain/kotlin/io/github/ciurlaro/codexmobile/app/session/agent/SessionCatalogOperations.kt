package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.agent.AgentClient
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentConversation
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentElicitation
import io.github.ciurlaro.codexmobile.agent.AgentElicitationAction
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentHookCatalog
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.agent.AgentPluginDetail
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull


internal suspend fun CodexSessionController.listModelsAction(): List<AgentModel> = agentClient.listModels()

internal suspend fun CodexSessionController.listSkillsAction(workingDirectory: String, forceReload: Boolean = false): AgentSkillCatalog =
    agentClient.listSkills(workingDirectory, forceReload)

internal suspend fun CodexSessionController.readSkillAction(path: String, offset: Long = 0) = agentClient.readSkill(path, offset)

internal suspend fun CodexSessionController.setSkillEnabledAction(path: String, enabled: Boolean) =
    runExternalOperation("Updating skill") { agentClient.setSkillEnabled(path, enabled) }

internal suspend fun CodexSessionController.listInstalledPluginsAction(
    workingDirectory: String?,
    forceRefresh: Boolean = false,
): AgentPluginCatalog = agentClient.listInstalledPlugins(workingDirectory, forceRefresh)

internal suspend fun CodexSessionController.listAvailablePluginsAction(
    workingDirectory: String?,
    forceRefresh: Boolean = false,
): AgentPluginCatalog = agentClient.listAvailablePlugins(workingDirectory, forceRefresh)

internal suspend fun CodexSessionController.readPluginAction(plugin: AgentPluginReference): AgentPluginDetail =
    agentClient.readPlugin(plugin)

internal suspend fun CodexSessionController.installPluginAction(plugin: AgentPluginReference): AgentPluginInstallResult =
    runPluginOperation("Installing ${plugin.name}") { agentClient.installPlugin(plugin) }

internal suspend fun CodexSessionController.uninstallPluginAction(plugin: AgentPluginReference): AgentPluginRemovalResult =
    runPluginOperation("Removing plugin") { agentClient.uninstallPlugin(plugin) }

internal suspend fun CodexSessionController.setPluginEnabledAction(pluginId: String, enabled: Boolean) =
    runPluginOperation("Updating plugin") { agentClient.setPluginEnabled(pluginId, enabled) }

internal suspend fun CodexSessionController.listConnectorsAction(forceReload: Boolean = false): List<AgentConnector> =
    agentClient.listConnectors(state.value.sessionId, forceReload)

internal suspend fun CodexSessionController.listMcpServersAction(): List<AgentMcpServer> = agentClient.listMcpServers()

internal suspend fun CodexSessionController.listHooksAction(workingDirectory: String): AgentHookCatalog =
    agentClient.listHooks(workingDirectory)

internal suspend fun CodexSessionController.setHookEnabledAction(key: String, enabled: Boolean) =
    runExternalOperation("Updating hook") { agentClient.setHookEnabled(key, enabled) }

internal suspend fun CodexSessionController.trustHookAction(key: String, currentHash: String) =
    runExternalOperation("Trusting hook") { agentClient.trustHook(key, currentHash) }

internal suspend fun CodexSessionController.startMcpOauthAction(serverName: String): String =
    runExternalOperation("Connecting to $serverName") {
        agentClient.startMcpOauth(serverName, state.value.sessionId)
    }

internal suspend fun CodexSessionController.listConversationsAction(): List<AgentConversationSummary> = agentClient.listSessions()

internal suspend fun CodexSessionController.readConversationAction(sessionId: SessionId): AgentConversation =
    agentClient.readSession(sessionId)

internal suspend fun CodexSessionController.renameConversationAction(sessionId: SessionId, title: String) =
    agentClient.renameSession(sessionId, title)

internal suspend fun CodexSessionController.deleteConversationAction(sessionId: SessionId) =
    agentClient.deleteSession(sessionId)
