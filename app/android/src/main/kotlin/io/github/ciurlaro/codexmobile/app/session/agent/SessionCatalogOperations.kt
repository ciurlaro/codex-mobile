package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentElicitation
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentHookCatalog
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentSkillPackageCatalog
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.platform.android.AndroidSkillPackageManager
import io.github.ciurlaro.codexmobile.platform.android.AndroidPluginMarketplaceManager
import java.util.concurrent.atomic.AtomicBoolean
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
    agentClient.listSkills(workingDirectory, forceReload).let { catalog ->
        catalog.copy(skills = catalog.skills.map { skill ->
            skill.copy(canUninstall = skillPackages?.canUninstall(skill) == true)
        })
    }

internal suspend fun CodexSessionController.readSkillAction(path: String, offset: Long = 0) = agentClient.readSkill(path, offset)

internal suspend fun CodexSessionController.setSkillEnabledAction(path: String, enabled: Boolean) =
    runExternalOperation("Updating skill") { agentClient.setSkillEnabled(path, enabled) }

internal suspend fun CodexSessionController.listAvailableSkillsAction(
    installedNames: Set<String>,
    forceRefresh: Boolean = false,
): AgentSkillPackageCatalog = requireNotNull(skillPackages) {
    "Skill packages are unavailable"
}.listAvailable(installedNames, forceRefresh)

internal suspend fun CodexSessionController.discoverGitHubSkillsAction(url: String): List<AgentSkillPackage> = requireNotNull(skillPackages) {
    "Skill packages are unavailable"
}.discoverGitHubSkills(url)

internal suspend fun CodexSessionController.readSkillPackageAction(packageInfo: AgentSkillPackage, offset: Long = 0) =
    requireNotNull(skillPackages) { "Skill packages are unavailable" }
        .readPackageSource(packageInfo, offset)

internal suspend fun CodexSessionController.installSkillAction(packageInfo: AgentSkillPackage) =
    runExternalOperation("Installing ${packageInfo.displayName}") {
        requireNotNull(skillPackages) { "Skill packages are unavailable" }.install(packageInfo)
    }

internal suspend fun CodexSessionController.uninstallSkillAction(skill: AgentSkill) =
    runExternalOperation("Removing ${skill.displayName}") {
        requireNotNull(skillPackages) { "Skill packages are unavailable" }.uninstall(skill)
    }

internal suspend fun CodexSessionController.listInstalledPluginsAction(
    workingDirectory: String?,
    forceRefresh: Boolean = false,
): AgentPluginCatalog = agentClient.listInstalledPlugins(workingDirectory, forceRefresh)

internal suspend fun CodexSessionController.listAvailablePluginsAction(
    workingDirectory: String?,
    forceRefresh: Boolean = false,
): AgentPluginCatalog = agentClient.listAvailablePlugins(workingDirectory, forceRefresh)

internal suspend fun CodexSessionController.addPluginMarketplaceAction(sourceUrl: String, reuseSnapshot: Boolean = false): String =
    runExternalOperation("Adding plugin source") {
        val marketplaces = requireNotNull(pluginMarketplaces) { "Plugin marketplaces are unavailable" }
        val snapshot = if (reuseSnapshot) marketplaces.snapshotOrReuse(sourceUrl) else marketplaces.snapshot(sourceUrl)
        val marketplaceName = marketplaces.marketplaceName(snapshot)
        agentClient.addPluginMarketplace(snapshot)
        marketplaceName
    }

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
