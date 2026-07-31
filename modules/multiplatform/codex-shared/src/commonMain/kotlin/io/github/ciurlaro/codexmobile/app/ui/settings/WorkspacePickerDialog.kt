package io.github.ciurlaro.codexmobile.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@Composable
fun WorkspacePickerDialog(
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
                        "📁 ${path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }}",
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
