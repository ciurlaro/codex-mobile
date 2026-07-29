package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary

@Composable
internal fun HistoryDialogs(
    renameConversation: AgentConversationSummary?,
    renameText: String,
    deleteConversation: AgentConversationSummary?,
    onRenameTextChanged: (String) -> Unit,
    onDismissRename: () -> Unit,
    onConfirmRename: (AgentConversationSummary) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (AgentConversationSummary) -> Unit,
) {
    renameConversation?.let { conversation ->
        AlertDialog(
            onDismissRequest = onDismissRename,
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { onRenameTextChanged(it.take(80)) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            dismissButton = { TextButton(onClick = onDismissRename) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = { onConfirmRename(conversation) },
                ) { Text("Rename") }
            },
        )
    }
    deleteConversation?.let { conversation ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Delete conversation?") },
            text = { Text("“${conversation.title}” will be permanently deleted.") },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = { onConfirmDelete(conversation) }) {
                    Text("Delete", color = ChatColors.Danger)
                }
            },
        )
    }
}
