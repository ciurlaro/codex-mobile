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
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.core.PLAN_CLIENT_MESSAGE_PREFIX
import io.github.ciurlaro.codexmobile.core.AgentHook
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
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


internal fun AppViewModel.loadPluginCatalogAction(forceReload: Boolean, allowFollowUp: Boolean = true) {
    val current = mutableState.value
    val controller = serviceController
    if (controller == null || !current.isAuthenticated) {
        val reconnecting = uiPreferences.hadAuthenticatedSession
        mutableState.update {
            it.copy(
                pluginCatalogStatus = if (reconnecting) {
                    PluginCatalogStatus.CONNECTING
                } else {
                    PluginCatalogStatus.ERROR
                },
                pluginCatalogError = if (reconnecting) null else "Sign in to load plugins.",
            )
        }
        if (reconnecting && !current.isAuthenticationInProgress) authenticate()
        return
    }
    if (pluginsJob?.isActive == true) {
        if (forceReload) pluginRefreshPending = true
        return
    }
    if (!forceReload && current.pluginCatalogStatus == PluginCatalogStatus.LIVE) return

    mutableState.update {
        it.copy(pluginCatalogStatus = PluginCatalogStatus.LOADING, pluginCatalogError = null)
    }
    val workingDirectory = container.platform.activeWorkspacePath()
    pluginsJob = scope.launch {
        val sourceErrors = reconcileEnabledPluginSources(controller)
        val installedResult = runCatching {
            controller.listInstalledPlugins(workingDirectory, forceRefresh = forceReload)
        }
        val availableResult = runCatching {
            controller.listAvailablePlugins(workingDirectory, forceRefresh = forceReload)
        }
        ensureActive()
        if (serviceController !== controller) return@launch

        val before = mutableState.value
        val installedCatalog = installedResult.getOrNull()
        val availableCatalog = availableResult.getOrNull()
        val installedCandidates = installedCatalog?.plugins ?: before.installedPlugins
        val availableCandidates = availableCatalog?.plugins ?: before.availablePlugins
        val merged = (availableCandidates + installedCandidates)
            .associateBy { it.reference.id }
            .values
        registerDiscoveredPluginSources(merged.toList())
        val sourceSelection = mutableState.value
        val installedIds = buildSet {
            installedCandidates.mapTo(this) { it.reference.id }
            merged.filter(AgentPluginSummary::installed).mapTo(this) { it.reference.id }
        }
        val installedPlugins = merged.filter { it.reference.id in installedIds }
        val availablePlugins = merged.filter { plugin ->
            plugin.reference.id !in installedIds &&
                sourceSelection.isPluginMarketplaceEnabled(plugin.reference.marketplaceName)
        }

        val errors = buildList {
            addAll(sourceErrors)
            addAll(installedCatalog?.errors.orEmpty())
            addAll(availableCatalog?.errors.orEmpty())
            installedResult.exceptionOrNull()?.let {
                add(it.message?.take(300) ?: "Installed plugins could not be refreshed")
            }
            availableResult.exceptionOrNull()?.let {
                add(it.message?.take(300) ?: "Available plugins could not be refreshed")
            }
        }.distinct()
        val live = sourceErrors.isEmpty() &&
            installedCatalog?.freshness == AgentCatalogFreshness.LIVE &&
            installedCatalog.errors.isEmpty() &&
            availableCatalog?.freshness == AgentCatalogFreshness.LIVE &&
            availableCatalog.errors.isEmpty()
        val status = when {
            live && (installedPlugins.isNotEmpty() || availablePlugins.isNotEmpty() || errors.isEmpty()) -> {
                PluginCatalogStatus.LIVE
            }
            installedPlugins.isNotEmpty() || availablePlugins.isNotEmpty() -> PluginCatalogStatus.STALE
            else -> PluginCatalogStatus.ERROR
        }
        val confirmedAvailableIds = availableCatalog
            ?.takeIf { it.freshness == AgentCatalogFreshness.LIVE && it.errors.isEmpty() }
            ?.plugins
            ?.filter(AgentPluginSummary::available)
            ?.mapTo(mutableSetOf()) { it.reference.id }
            .orEmpty()
        mutableState.update {
            it.copy(
                installedPlugins = installedPlugins,
                availablePlugins = availablePlugins,
                pluginCatalogStatus = status,
                pluginCatalogError = errors.joinToString("\n").ifBlank { null },
                unavailablePluginIds = it.unavailablePluginIds - confirmedAvailableIds,
            )
        }
        if (live) reconcileStoredPluginSetups(mutableState.value.connectors, installedIds)

        val cached = !forceReload && listOfNotNull(installedCatalog, availableCatalog).any {
            it.freshness != AgentCatalogFreshness.LIVE
        }
        val followUp = allowFollowUp && (pluginRefreshPending || cached)
        pluginRefreshPending = false
        pluginsJob = null
        if (followUp) loadPluginCatalog(forceReload = true, allowFollowUp = false)
    }
}

internal suspend fun AppViewModel.reconcileEnabledPluginSourcesAction(controller: CodexSessionController): List<String> {
    val current = mutableState.value
    val sources = buildList {
        if (CODEX_MOBILE_PLUGIN_SOURCE_ID in current.enabledExtensionSourceIds) {
            add(CODEX_MOBILE_PLUGIN_SOURCE_ID to CODEX_MOBILE_PLUGIN_SOURCE_URL)
        }
        current.customExtensionSources.filter {
            it.supportsPlugins && it.id in current.enabledExtensionSourceIds
        }.forEach { add(it.id to it.url) }
    }
    return buildList {
        sources.filterNot { it.first in reconciledPluginSourceIds }.forEach { (id, url) ->
            runCatching { controller.addPluginMarketplace(url, reuseSnapshot = true) }
                .onSuccess { reconciledPluginSourceIds += id }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    add(error.message?.take(300) ?: "Plugin source could not be restored")
                }
        }
    }
}

internal fun AppViewModel.registerDiscoveredPluginSourcesAction(plugins: List<AgentPluginSummary>) {
    val mappedMarketplaceNames = mutableState.value.customExtensionSources
        .mapNotNull(CustomExtensionSource::marketplaceName)
        .map(::canonicalPluginSourceId)
        .toSet()
    val discovered = plugins.map { canonicalPluginSourceId(it.reference.marketplaceName) }
        .filter(String::isNotBlank)
        .filterNot { it in mappedMarketplaceNames }
        .toSet()
    if (discovered.isEmpty()) return
    mutableState.update {
        val newIds = discovered - it.knownExtensionSourceIds
        it.copy(
            knownExtensionSourceIds = it.knownExtensionSourceIds + discovered,
            enabledExtensionSourceIds = it.enabledExtensionSourceIds + (newIds - OPENAI_PLUGIN_SOURCE_ID),
        )
    }
    persistExtensionSourceSelection()
}

internal fun AppViewModel.persistExtensionSourceSelectionAction() {
    val current = mutableState.value
    uiPreferences.saveExtensionSourceSelection(
        current.knownExtensionSourceIds,
        current.enabledExtensionSourceIds,
        current.customExtensionSources,
    )
}
