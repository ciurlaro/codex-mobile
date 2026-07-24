package io.github.ciurlaro.codexmobile.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentElicitation
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentFormField
import io.github.ciurlaro.codexmobile.core.AgentFormFieldType
import io.github.ciurlaro.codexmobile.core.AgentFormValue
import io.github.ciurlaro.codexmobile.core.AgentMcpAuthStatus
import java.io.File

@Composable
internal fun CodexApprovalDialog(
    approval: AgentEvent.ApprovalRequested,
    onDecision: (AgentApprovalDecision) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDecision(AgentApprovalDecision.DECLINE) },
        title = { Text(approval.title.toApprovalDisplayText()) },
        text = {
            Text(
                approval.details.toApprovalDisplayText(),
                fontFamily = FontFamily.Monospace,
            )
        },
        confirmButton = {
            Button(onClick = { onDecision(AgentApprovalDecision.ACCEPT) }) { Text("Allow") }
        },
        dismissButton = {
            Button(onClick = { onDecision(AgentApprovalDecision.DECLINE) }) { Text("Deny") }
        },
    )
}

@Composable
internal fun IntegrationsDialog(
    state: MainUiState,
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

@Composable
internal fun ElicitationDialog(
    elicitation: AgentElicitation,
    onResponse: (AgentElicitationResponse) -> Unit,
) {
    val form = elicitation.form
    val answers = remember(elicitation.requestId) {
        mutableStateMapOf<String, AgentFormValue>().apply {
            form.orEmpty().forEach { field -> field.defaultValue?.let { put(field.name, it) } }
        }
    }
    AlertDialog(
        onDismissRequest = {
            onResponse(AgentElicitationResponse(AgentElicitationAction.CANCEL))
        },
        title = { Text(elicitation.serverName) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(elicitation.message)
                if (elicitation.url != null) {
                    Text("Complete the secure authorization window, or cancel here.")
                }
                form.orEmpty().forEach { field ->
                    Text(field.title)
                    field.description?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (field.type) {
                        AgentFormFieldType.STRING,
                        AgentFormFieldType.NUMBER,
                        AgentFormFieldType.INTEGER,
                        -> OutlinedTextField(
                            value = when (val value = answers[field.name]) {
                                is AgentFormValue.Text -> value.value
                                is AgentFormValue.Number -> value.value.toString()
                                else -> ""
                            },
                            onValueChange = { value ->
                                answers[field.name] = when (field.type) {
                                    AgentFormFieldType.STRING -> AgentFormValue.Text(value)
                                    else -> value.toDoubleOrNull()?.let(AgentFormValue::Number)
                                        ?: AgentFormValue.Text(value)
                                }
                            },
                            singleLine = true,
                        )
                        AgentFormFieldType.BOOLEAN -> androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            val checked = (answers[field.name] as? AgentFormValue.BooleanValue)?.value == true
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { answers[field.name] = AgentFormValue.BooleanValue(it) },
                            )
                            Text(if (checked) "Yes" else "No")
                        }
                        AgentFormFieldType.SINGLE_SELECT -> field.options.forEach { option ->
                            val selected = (answers[field.name] as? AgentFormValue.Text)?.value == option.value
                            Text(
                                (if (selected) "✓ " else "") + option.title,
                                Modifier.fillMaxWidth().clickable {
                                    answers[field.name] = AgentFormValue.Text(option.value)
                                }.padding(vertical = 8.dp),
                            )
                        }
                        AgentFormFieldType.MULTI_SELECT -> field.options.forEach { option ->
                            val selected = (answers[field.name] as? AgentFormValue.TextList)
                                ?.value.orEmpty()
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = option.value in selected,
                                    onCheckedChange = { checked ->
                                        answers[field.name] = AgentFormValue.TextList(
                                            if (checked) selected + option.value else selected - option.value,
                                        )
                                    },
                                )
                                Text(option.title)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (form != null) {
                val valid = form.all { field -> isValidElicitationAnswer(field, answers[field.name]) }
                Button(
                    enabled = valid,
                    onClick = {
                        onResponse(AgentElicitationResponse(AgentElicitationAction.ACCEPT, answers.toMap()))
                    },
                ) { Text("Continue") }
            }
        },
        dismissButton = {
            Button(onClick = {
                onResponse(AgentElicitationResponse(AgentElicitationAction.CANCEL))
            }) { Text("Cancel") }
        },
    )
}

internal fun isValidElicitationAnswer(field: AgentFormField, value: AgentFormValue?): Boolean {
    if (value == null) return !field.required
    val minimum = field.minimum
    val maximum = field.maximum
    return when (field.type) {
        AgentFormFieldType.STRING -> value is AgentFormValue.Text && (!field.required || value.value.isNotBlank())
        AgentFormFieldType.NUMBER -> (value as? AgentFormValue.Number)?.value?.let {
            (minimum == null || it >= minimum) && (maximum == null || it <= maximum)
        } == true
        AgentFormFieldType.INTEGER -> (value as? AgentFormValue.Number)?.value?.let {
            it % 1.0 == 0.0 && (minimum == null || it >= minimum) &&
                (maximum == null || it <= maximum)
        } == true
        AgentFormFieldType.BOOLEAN -> value is AgentFormValue.BooleanValue
        AgentFormFieldType.SINGLE_SELECT -> value is AgentFormValue.Text &&
            field.options.any { it.value == value.value }
        AgentFormFieldType.MULTI_SELECT -> value is AgentFormValue.TextList &&
            value.value.all { selected -> field.options.any { it.value == selected } }
    }
}

@Composable
internal fun EraseDataDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Erase all Codex Mobile data?") },
        text = {
            Text(
                "This signs you out and permanently erases app credentials, conversation " +
                    "history, settings, and integration data. Files in shared storage are not deleted.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Erase app data")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Keep data")
            }
        },
    )
}

@Composable
internal fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy details") },
        text = { PrivacyDisclosure() },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        },
    )
}

@Composable
internal fun WorkspacePickerDialog(
    currentPath: String?,
    directories: List<String>,
    parent: String?,
    onOpen: (String) -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose workspace") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(currentPath ?: "Shared storage", color = MaterialTheme.colorScheme.onSurfaceVariant)
                parent?.let { path ->
                    Text(
                        "↑ Parent folder",
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(path) }.padding(vertical = 12.dp),
                    )
                }
                directories.forEach { path ->
                    Text(
                        "📁 ${File(path).name.ifBlank { path }}",
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(path) }.padding(vertical = 12.dp),
                    )
                }
                if (directories.isEmpty()) {
                    Text("No subfolders", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSelect, enabled = currentPath != null) { Text("Use this folder") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PrivacyDisclosure() {
    Column(
        Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrivacySection(
            "OpenAI",
            "Prompts, responses, shell output, file text or bytes requested by Codex, rendered pages, " +
                "images, and tool results are sent to OpenAI as part of the Codex session.",
        )
        PrivacySection(
            "Storage access",
            "The selected workspace is Codex's starting folder, not a sandbox. With all-files access, " +
                "ordinary Codex shell commands can navigate to other accessible shared-storage locations; " +
                "provider file tools stay inside the selected workspace. Manage the permission in Android Settings.",
        )
        PrivacySection(
            "Local storage and logs",
            "ChatGPT credentials, conversation state, mutation recovery state, settings, and integration data " +
                "stay in app-private storage excluded from Android backup. Prompt and provider contents are " +
                "not written to Codex Mobile logs.",
        )
        PrivacySection(
            "Integrations",
            "Enabled plugins receive only their typed requests. Connected services keep their own authorization " +
                "until you disconnect them; erasing app data removes local state but does not prove remote logout.",
        )
        PrivacySection(
            "Plugins and external providers",
            "Installed plugins and connectors may send the prompt and selected context needed for a request " +
                "to external providers under their own privacy policies and terms. Codex Mobile lists the " +
                "plugin catalogs made available by its bundled Codex server.",
        )
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
    ) {
        Text(if (expanded) "− $title" else "+ $title")
        if (expanded) Text(body, modifier = Modifier.padding(top = 6.dp))
    }
}
