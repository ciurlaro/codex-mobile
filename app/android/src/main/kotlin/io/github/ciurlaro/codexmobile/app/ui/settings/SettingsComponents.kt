package io.github.ciurlaro.codexmobile.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.platform.android.ProviderSettingsEntry

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(ChatDimensions.CardCorner),
            color = ChatColors.ElevatedStrong,
            border = BorderStroke(1.dp, ChatColors.Border),
        ) { Column(content = content) }
    }
}

@Composable
internal fun SettingsRow(
    glyph: IconGlyph,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val action = if (onClick == null || !enabled) Modifier else Modifier.clickable(onClick = onClick)
    val contentColor = when {
        !enabled -> ChatColors.Secondary.copy(alpha = 0.5f)
        danger -> ChatColors.Danger
        else -> ChatColors.Primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(action)
            .semantics(mergeDescendants = true) { if (onClick != null) role = Role.Button }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(glyph, Modifier.size(25.dp), contentColor)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = contentColor, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    color = if (enabled) ChatColors.Secondary else ChatColors.Secondary.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null && enabled) {
            Spacer(Modifier.width(8.dp))
            AppIcon(IconGlyph.CHEVRON_RIGHT, Modifier.size(18.dp), ChatColors.Secondary)
        }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        color = ChatColors.Background,
        thickness = 2.dp,
        modifier = Modifier.padding(start = 60.dp),
    )
}

@Composable
internal fun PluginSettingsDialog(
    providers: List<ProviderSettingsEntry>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plugin settings") },
        text = {
            Column {
                providers.forEachIndexed { index, provider ->
                    SettingsRow(
                        glyph = IconGlyph.PUZZLE,
                        title = provider.displayName,
                        subtitle = provider.message ?: if (provider.removalNeedsRetry) {
                            "Removal needs retry"
                        } else {
                            "Configure plugin"
                        },
                        onClick = { onSelect(provider.pluginId) },
                    )
                    if (index < providers.lastIndex) SettingsDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
