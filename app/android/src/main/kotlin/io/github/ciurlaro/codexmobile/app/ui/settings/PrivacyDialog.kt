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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
