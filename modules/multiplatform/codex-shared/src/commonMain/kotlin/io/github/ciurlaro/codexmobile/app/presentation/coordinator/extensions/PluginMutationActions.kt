package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionRemoval
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import kotlinx.coroutines.flow.update

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
                it.installedPlugins.filterNot { candidate -> candidate.reference.id == plugin.id } + summary
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
            pendingConnectorIds.mapNotNull { id ->
                latest[id] ?: requiredConnectors.firstOrNull { it.id == id }
            },
        )
    }
}

internal fun AppViewModel.connectPluginAction(plugin: AgentPluginReference) {
    val operationId = "connect:${plugin.id}"
    val current = mutableState.value
    if (
        current.extensionOperationId == operationId ||
        current.connectorAuthName in current.pendingPluginSetups[plugin.id].orEmpty()
    ) {
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
                it.copy(statusMessage = "Plugin setup complete", extensionStatus = ExtensionStatus.INSTALLED)
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

internal fun AppViewModel.uninstallPluginAction(
    plugin: AgentPluginReference,
    displayName: String,
) = extensionMutation(
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
            availablePlugins = if (result.completed && removed != null) {
                it.availablePlugins.filterNot { candidate -> candidate.reference.id == plugin.id } +
                    removed.copy(installed = false, enabled = false)
            } else {
                it.availablePlugins
            },
            pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
        )
    }
    showExtensionNotice(notice, isError = !result.completed)
    loadPluginCatalog(forceReload = true)
}

internal fun AppViewModel.requestUninstallPluginAction(
    plugin: AgentPluginReference,
    displayName: String,
) {
    mutableState.update {
        it.copy(pendingExtensionRemoval = ExtensionRemoval.Plugin(plugin, displayName))
    }
}

internal fun AppViewModel.dismissExtensionRemovalAction() {
    mutableState.update { it.copy(pendingExtensionRemoval = null) }
}

internal fun AppViewModel.confirmExtensionRemovalAction() {
    val removal = mutableState.value.pendingExtensionRemoval as? ExtensionRemoval.Plugin ?: return
    mutableState.update { it.copy(pendingExtensionRemoval = null) }
    uninstallPlugin(removal.plugin, removal.displayName)
}
