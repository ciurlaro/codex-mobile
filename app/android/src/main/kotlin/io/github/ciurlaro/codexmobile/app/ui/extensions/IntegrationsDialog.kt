package io.github.ciurlaro.codexmobile.app.ui.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.core.AgentMcpAuthStatus

@Composable
internal fun IntegrationsDialog(
    state: AppUiState,
    onDismiss: () -> Unit,
    onConnectApp: (String) -> Unit,
    onConnectMcp: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Integrations") },
        text = {
            Column {
                state.connectors.forEach { connector ->
                    IntegrationRow(
                        name = connector.name,
                        status = if (connector.isAccessible) "Connected" else "Connection required",
                        canConnect = !connector.isAccessible && connector.installUrl != null,
                        onConnect = { onConnectApp(connector.id) },
                    )
                }
                state.mcpServers.forEach { server ->
                    IntegrationRow(
                        name = server.displayName,
                        status = when (server.authStatus) {
                            AgentMcpAuthStatus.NOT_LOGGED_IN -> "Connection required"
                            AgentMcpAuthStatus.UNSUPPORTED -> "Managed outside Codex Mobile"
                            else -> "Connected"
                        },
                        canConnect = server.authStatus == AgentMcpAuthStatus.NOT_LOGGED_IN,
                        onConnect = { onConnectMcp(server.name) },
                    )
                }
                if (state.connectors.isEmpty() && state.mcpServers.isEmpty()) Text(
                    "No app or MCP integrations are available.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun IntegrationRow(
    name: String,
    status: String,
    canConnect: Boolean,
    onConnect: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name)
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canConnect) Button(onClick = onConnect) { Text("Connect") }
    }
}
