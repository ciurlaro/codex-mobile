package io.github.ciurlaro.codexmobile.app.ui.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EraseDataDialog(
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
