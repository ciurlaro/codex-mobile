package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.app.lifecycle.CodexMobileApplication
import io.github.ciurlaro.codexmobile.app.persistence.AppPreferencesStore
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.planCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.withoutActiveInvocationToken
import io.github.ciurlaro.codexmobile.app.presentation.invocation.withRecentInvocation
import io.github.ciurlaro.codexmobile.app.presentation.mapper.toChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionActionError
import io.github.ciurlaro.codexmobile.app.presentation.model.CustomExtensionSource
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.afterExpiry
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionRemoval
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_URL
import io.github.ciurlaro.codexmobile.app.presentation.model.OPENAI_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.canonicalPluginSourceId
import io.github.ciurlaro.codexmobile.app.presentation.model.enabledMarketplaceNames
import io.github.ciurlaro.codexmobile.app.presentation.model.initialExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.reconcilePendingPluginSetups
import io.github.ciurlaro.codexmobile.app.presentation.state.withNewChat
import io.github.ciurlaro.codexmobile.app.presentation.state.withStreamingAssistant
import io.github.ciurlaro.codexmobile.app.presentation.state.withSubmittedTurn
import io.github.ciurlaro.codexmobile.app.presentation.state.withoutConversation
import io.github.ciurlaro.codexmobile.app.presentation.state.connectorsNeedingOnUseAuthentication
import io.github.ciurlaro.codexmobile.app.presentation.state.selectedModelOrNull
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionController
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionState
import io.github.ciurlaro.codexmobile.app.session.background.CodexForegroundService
import io.github.ciurlaro.codexmobile.app.session.background.CodexServiceConnection
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.PLAN_CLIENT_MESSAGE_PREFIX
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginSummary
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.SessionId
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull


internal fun AppViewModel.installPluginAction(plugin: AgentPluginReference) = extensionMutation(
    "plugin:${plugin.id}",
    "Plugin could not be installed",
) {
    val controller = serviceController ?: return@extensionMutation
    val installed = mutableState.value.availablePlugins
        .firstOrNull { it.reference.id == plugin.id }
        ?.copy(installed = true, enabled = true)
    val result = controller.installPlugin(plugin)
    val requiredConnectors = if (result.authPolicy == AgentPluginAuthPolicy.ON_INSTALL) {
        val detailConnectors = runCatching { controller.readPlugin(plugin).connectors }.getOrDefault(emptyList())
        (detailConnectors + result.connectorsNeedingAuthentication)
            .associateBy(AgentConnector::id)
            .values
            .toList()
    } else {
        emptyList()
    }
    val displayName = installed?.displayName ?: plugin.name.replaceFirstChar(Char::uppercase)
    mutableState.update {
        it.copy(
            installedPlugins = installed?.let { summary ->
                (it.installedPlugins.filterNot { candidate -> candidate.reference.id == plugin.id } + summary)
            } ?: it.installedPlugins,
            availablePlugins = it.availablePlugins.filterNot { candidate -> candidate.reference.id == plugin.id },
            unavailablePluginIds = it.unavailablePluginIds - plugin.id,
            extensionActionError = null,
        )
    }
    if (requiredConnectors.isNotEmpty()) {
        integrationsLoaded = true
        setPendingPluginSetup(plugin.id, requiredConnectors.mapTo(mutableSetOf(), AgentConnector::id))
        refreshConnectors(controller, forceReload = true)
    }
    val pendingConnectorIds = mutableState.value.pendingPluginSetups[plugin.id].orEmpty()
    val setupPending = pendingConnectorIds.isNotEmpty()
    val notice = result.message ?: if (setupPending) {
        "$displayName installed · setup required"
    } else {
        "$displayName installed"
    }
    mutableState.update {
        it.copy(
            statusMessage = notice,
            extensionStatus = if (setupPending) ExtensionStatus.SETUP_PENDING else it.extensionStatus,
        )
    }
    showExtensionNotice(notice)
    loadPluginCatalog(forceReload = true)
    if (setupPending) {
        val latest = mutableState.value.connectors.associateBy(AgentConnector::id)
        enqueueConnectorAuthentication(
            pendingConnectorIds.mapNotNull { id -> latest[id] ?: requiredConnectors.firstOrNull { it.id == id } },
        )
    }
}

internal fun AppViewModel.connectPluginAction(plugin: AgentPluginReference) {
    val operationId = "connect:${plugin.id}"
    val current = mutableState.value
    if (current.extensionOperationId == operationId || current.connectorAuthName in current.pendingPluginSetups[plugin.id].orEmpty()) {
        return
    }
    extensionMutation(operationId, "Plugin setup could not be opened") {
        val controller = serviceController ?: error("Codex is not ready")
        integrationsLoaded = true
        val details = runCatching { controller.readPlugin(plugin).connectors }.getOrDefault(emptyList())
        val refreshed = refreshConnectors(controller, forceReload = true).orEmpty()
        val pendingIds = mutableState.value.pendingPluginSetups[plugin.id].orEmpty()
        if (pendingIds.isEmpty()) {
            mutableState.update {
                it.copy(
                    statusMessage = "Plugin setup complete",
                    extensionStatus = ExtensionStatus.INSTALLED,
                )
            }
            showExtensionNotice("Plugin setup complete")
            return@extensionMutation
        }
        val connectors = (details + refreshed)
            .associateBy(AgentConnector::id)
            .filterKeys { it in pendingIds }
            .values
            .filter { !it.isAccessible && it.installUrl != null }
        check(connectors.isNotEmpty()) { "A connection link is not available yet; refresh and try again" }
        enqueueConnectorAuthentication(connectors)
        mutableState.update { it.copy(statusMessage = "Complete plugin setup in the secure window") }
    }
}

internal fun AppViewModel.uninstallPluginAction(plugin: AgentPluginReference, displayName: String) = extensionMutation(
    "plugin:${plugin.id}",
    "Plugin could not be removed",
) {
    val removed = mutableState.value.installedPlugins.firstOrNull { it.reference.id == plugin.id }
    val result = serviceController?.uninstallPlugin(plugin) ?: return@extensionMutation
    val notice = result.message ?: if (result.completed) {
        "$displayName uninstalled"
    } else {
        "$displayName could not be uninstalled"
    }
    if (result.completed) setPendingPluginSetup(plugin.id, emptySet())
    mutableState.update {
        it.copy(
            statusMessage = notice,
            installedPlugins = if (result.completed) {
                it.installedPlugins.filterNot { candidate -> candidate.reference.id == plugin.id }
            } else {
                it.installedPlugins
            },
            availablePlugins = if (result.completed && removed != null && it.isPluginMarketplaceEnabled(
                    removed.reference.marketplaceName,
                )
            ) {
                (it.availablePlugins.filterNot { candidate -> candidate.reference.id == plugin.id } +
                    removed.copy(installed = false, enabled = false))
            } else {
                it.availablePlugins
            },
            pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
            providerSettings = container.platform.providerSettings(),
        )
    }
    showExtensionNotice(notice, isError = !result.completed)
    loadPluginCatalog(forceReload = true)
}

internal fun AppViewModel.requestUninstallPluginAction(plugin: AgentPluginReference, displayName: String) {
    mutableState.update {
        it.copy(pendingExtensionRemoval = ExtensionRemoval.Plugin(plugin, displayName))
    }
}

internal fun AppViewModel.dismissExtensionRemovalAction() {
    mutableState.update { it.copy(pendingExtensionRemoval = null) }
}

internal fun AppViewModel.confirmExtensionRemovalAction() {
    when (val removal = mutableState.value.pendingExtensionRemoval) {
        is ExtensionRemoval.Skill -> {
            mutableState.update { it.copy(pendingExtensionRemoval = null) }
            extensionMutation(
                "skill:${removal.skill.path}",
                "Skill could not be removed",
            ) {
                serviceController?.uninstallSkill(removal.skill)
                mutableState.update {
                    it.copy(
                        skills = it.skills.filterNot { candidate -> candidate.path == removal.skill.path },
                    )
                }
                mutableState.update { it.copy(availableSkillsLoaded = false) }
                loadAvailableSkills(forceReload = false)
            }
        }
        is ExtensionRemoval.Plugin -> {
            mutableState.update { it.copy(pendingExtensionRemoval = null) }
            uninstallPlugin(removal.plugin, removal.displayName)
        }
        null -> Unit
    }
}
