package io.github.ciurlaro.codexmobile.app.presentation.model

import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginSummary

enum class ExtensionType(val label: String) {
    SKILLS("Skills"), PLUGINS("Plugins"),
}

enum class ExtensionStatus(val label: String) {
    INSTALLED("Installed"), SETUP_PENDING("Setup pending"), UNINSTALLED("Market"), UNAVAILABLE("Unavailable"),
}

enum class PluginCatalogStatus { NOT_LOADED, CONNECTING, LOADING, LIVE, STALE, ERROR }

sealed interface ExtensionRemoval {
    val displayName: String

    data class Plugin(
        val plugin: AgentPluginReference,
        override val displayName: String,
    ) : ExtensionRemoval
}

data class ExtensionActionError(val operationId: String, val message: String)

data class ExtensionNotice(val message: String, val isError: Boolean = false)

internal fun ExtensionNotice?.afterExpiry(expiring: ExtensionNotice): ExtensionNotice? =
    takeUnless { it == expiring }

internal fun AgentPluginSummary.uninstalledStatus(
    installedIds: Set<String>,
    unavailableIds: Set<String>,
): ExtensionStatus? = when {
    reference.id in installedIds -> null
    !available || reference.id in unavailableIds -> ExtensionStatus.UNAVAILABLE
    else -> ExtensionStatus.UNINSTALLED
}

internal fun reconcilePendingPluginSetups(
    pending: Map<String, Set<String>>,
    connectors: List<AgentConnector>,
    installedPluginIds: Set<String>? = null,
): Map<String, Set<String>> {
    val accessibleConnectorIds = connectors.filter { it.isAccessible }.mapTo(mutableSetOf()) { it.id }
    return pending.mapNotNull { (pluginId, connectorIds) ->
        val remaining = connectorIds - accessibleConnectorIds
        (pluginId to remaining).takeIf {
            remaining.isNotEmpty() && (installedPluginIds == null || pluginId in installedPluginIds)
        }
    }.toMap()
}
