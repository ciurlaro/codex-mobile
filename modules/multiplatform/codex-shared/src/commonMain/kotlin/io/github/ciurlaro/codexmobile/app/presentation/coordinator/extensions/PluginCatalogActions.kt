package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentPluginSummary
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun AppViewModel.loadPluginCatalogAction(
    forceReload: Boolean,
    allowFollowUp: Boolean = true,
) {
    val current = mutableState.value
    val controller = serviceController
    if (controller == null || !current.isAuthenticated) {
        val reconnecting = preferenceState.hadAuthenticatedSession
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
    val workingDirectory = platform.activeWorkspacePath()
    pluginsJob = scope.launch {
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
        val installedIds = buildSet {
            installedCandidates.mapTo(this) { it.reference.id }
            merged.filter(AgentPluginSummary::installed).mapTo(this) { it.reference.id }
        }
        val installedPlugins = merged.filter { it.reference.id in installedIds }
        val availablePlugins = merged.filterNot { it.reference.id in installedIds }
        val errors = buildList {
            addAll(installedCatalog?.errors.orEmpty())
            addAll(availableCatalog?.errors.orEmpty())
            installedResult.exceptionOrNull()?.let {
                add(it.message?.take(300) ?: "Installed plugins could not be refreshed")
            }
            availableResult.exceptionOrNull()?.let {
                add(it.message?.take(300) ?: "Available plugins could not be refreshed")
            }
        }.distinct()
        val live = installedCatalog?.freshness == AgentCatalogFreshness.LIVE &&
            installedCatalog.errors.isEmpty() &&
            availableCatalog?.freshness == AgentCatalogFreshness.LIVE &&
            availableCatalog.errors.isEmpty()
        val status = when {
            live -> PluginCatalogStatus.LIVE
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
