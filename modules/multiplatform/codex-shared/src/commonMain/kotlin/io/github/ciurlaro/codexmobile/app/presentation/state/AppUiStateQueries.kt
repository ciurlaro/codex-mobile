package io.github.ciurlaro.codexmobile.app.presentation.state

import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy

internal fun AppUiState.selectedModelOrNull(): AgentModel? =
    models.firstOrNull { it.id == selectedModel }

internal fun AppUiState.connectorsNeedingOnUseAuthentication(): List<AgentConnector> {
    val selectedPlugins = selectedInvocations.filterIsInstance<AgentInvocation.Plugin>().mapNotNull { invocation ->
        plugins.firstOrNull { it.reference.uri == invocation.uri }
    }
    val selectedPendingConnectorIds = selectedPlugins
        .flatMapTo(mutableSetOf()) { pendingPluginSetups[it.reference.id].orEmpty() }
    return connectors.filter { connector ->
        !connector.isAccessible && connector.installUrl != null && (
            connector.id in selectedPendingConnectorIds || selectedPlugins.any { plugin ->
                plugin.authPolicy == AgentPluginAuthPolicy.ON_USE && (
                    connector.id.equals(plugin.reference.name, ignoreCase = true) ||
                        connector.pluginNames.any {
                            it.equals(plugin.displayName, ignoreCase = true) ||
                                it.equals(plugin.reference.name, ignoreCase = true)
                        }
                )
            }
        )
    }
}
