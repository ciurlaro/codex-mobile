package io.github.ciurlaro.codexmobile.app.presentation.state

import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy

internal fun AppUiState.selectedModelOrNull(): AgentModel? =
    models.firstOrNull { it.id == selectedModel }

internal fun AppUiState.connectorsNeedingOnUseAuthentication(): List<AgentConnector> {
    val selectedPlugins = selectedInvocations.filterIsInstance<AgentInvocation.Plugin>().mapNotNull { invocation ->
        plugins.firstOrNull { it.reference.uri == invocation.uri }
            ?.takeIf { it.authPolicy == AgentPluginAuthPolicy.ON_USE }
    }
    return connectors.filter { connector ->
        !connector.isAccessible && connector.installUrl != null && selectedPlugins.any { plugin ->
            connector.id.equals(plugin.reference.name, ignoreCase = true) ||
                connector.pluginNames.any {
                    it.equals(plugin.displayName, ignoreCase = true) ||
                        it.equals(plugin.reference.name, ignoreCase = true)
                }
        }
    }
}
