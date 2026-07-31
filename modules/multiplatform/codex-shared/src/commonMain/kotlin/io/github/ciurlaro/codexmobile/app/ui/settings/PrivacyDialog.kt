package io.github.ciurlaro.codexmobile.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions

@Composable
fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Privacy details", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                CircleIconButton("Close privacy details", IconGlyph.CLOSE, onClick = onDismiss)
            }
        },
        text = { PrivacyDisclosure() },
        confirmButton = {},
        containerColor = ChatColors.Elevated,
        shape = RoundedCornerShape(ChatDimensions.CardCorner),
    )
}

@Composable
private fun PrivacyDisclosure() {
    var expandedTitle by rememberSaveable { mutableStateOf<String?>("OpenAI") }
    Surface(
        color = ChatColors.ElevatedStrong,
        shape = RoundedCornerShape(ChatDimensions.CardCorner),
        border = BorderStroke(1.dp, ChatColors.Border),
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PrivacySection(
                "OpenAI",
                IconGlyph.SPARKLES,
                "Prompts, responses, shell output, file text or bytes requested by Codex, rendered pages, " +
                    "images, and tool results are sent to OpenAI as part of the Codex session.",
                expanded = expandedTitle == "OpenAI",
                onToggle = { expandedTitle = "OpenAI".takeUnless { expandedTitle == it } },
            )
            HorizontalDivider(color = ChatColors.Border)
            PrivacySection(
                "Storage access",
                IconGlyph.STORAGE,
                "The selected workspace is Codex's starting folder, not a sandbox. With all-files access, " +
                    "ordinary Codex shell commands can navigate to other accessible shared-storage locations. " +
                    "Manage the permission in Android Settings.",
                expanded = expandedTitle == "Storage access",
                onToggle = { expandedTitle = "Storage access".takeUnless { expandedTitle == it } },
            )
            HorizontalDivider(color = ChatColors.Border)
            PrivacySection(
                "Local storage and logs",
                IconGlyph.LOCK,
                "ChatGPT credentials, conversation state, settings, and integration data stay in app-private " +
                    "storage excluded from Android backup. Prompt contents are not written to Codex Mobile logs.",
                expanded = expandedTitle == "Local storage and logs",
                onToggle = { expandedTitle = "Local storage and logs".takeUnless { expandedTitle == it } },
            )
            HorizontalDivider(color = ChatColors.Border)
            PrivacySection(
                "Integrations",
                IconGlyph.LINK,
                "Enabled plugins receive only their typed requests. Connected services keep their own authorization " +
                    "until you disconnect them; erasing app data removes local state but does not prove remote logout.",
                expanded = expandedTitle == "Integrations",
                onToggle = { expandedTitle = "Integrations".takeUnless { expandedTitle == it } },
            )
            HorizontalDivider(color = ChatColors.Border)
            PrivacySection(
                "Plugins and connectors",
                IconGlyph.PUZZLE,
                "Installed plugins and connectors may send the prompt and selected context needed for a request " +
                    "to external services under their own privacy policies and terms. Codex Mobile lists the " +
                    "official plugin catalog made available by its bundled Codex server.",
                expanded = expandedTitle == "Plugins and connectors",
                onToggle = { expandedTitle = "Plugins and connectors".takeUnless { expandedTitle == it } },
            )
        }
    }
}

@Composable
private fun PrivacySection(
    title: String,
    glyph: IconGlyph,
    body: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrivacyIcon(glyph)
            Text(
                title,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                fontWeight = FontWeight.Medium,
            )
            AppIcon(
                if (expanded) IconGlyph.CHEVRON_DOWN else IconGlyph.CHEVRON_RIGHT,
                Modifier.size(18.dp),
                ChatColors.Secondary,
            )
        }
        if (expanded) {
            Text(
                body,
                modifier = Modifier.padding(start = 46.dp, top = 10.dp, end = 4.dp),
                color = ChatColors.Secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PrivacyIcon(glyph: IconGlyph) {
    Surface(
        shape = CircleShape,
        color = ChatColors.Accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, ChatColors.Accent.copy(alpha = 0.55f)),
        modifier = Modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIcon(glyph, Modifier.size(18.dp), ChatColors.Accent)
        }
    }
}
