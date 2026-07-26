package io.github.ciurlaro.codexmobile.app.ui.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSourceUi
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions

@Composable
internal fun ExtensionSourcesScreen(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
    onAddSource: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        ExtensionTopBar("Sources") { onEvent(AppUiEvent.CloseExtensionSources) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ChatDimensions.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("source-introduction") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Extension sources", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Choose which skill and plugin catalogs are available. Installed extensions remain available.",
                        color = ChatColors.Secondary,
                    )
                }
            }
            item("add-source") { AddSourceCard(onAddSource) }
            items(state.extensionSources, key = ExtensionSourceUi::id) { source ->
                ExtensionSourceCard(
                    source = source,
                    enabled = !state.isExtensionSourceLoading,
                    onToggle = { onEvent(AppUiEvent.ToggleExtensionSource(source.id, it)) },
                )
            }
            state.extensionSourceError?.let { error ->
                item("source-error") {
                    Text(error, color = ChatColors.Danger, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.extensionNotice?.let { notice ->
                item("source-notice") { ExtensionNoticeCard(notice.message, notice.isError) }
            }
        }
    }
}

@Composable
private fun AddSourceCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = ChatColors.Elevated,
        shape = RoundedCornerShape(ChatDimensions.CardCorner),
        border = BorderStroke(1.dp, ChatColors.Border),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(IconGlyph.GLOBE, ChatColors.Primary)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Add your own source", fontWeight = FontWeight.SemiBold)
                Text(
                    "Connect a public GitHub repository containing extensions.",
                    color = ChatColors.Secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AppIcon(IconGlyph.PLUS, Modifier.size(24.dp), ChatColors.Accent)
        }
    }
}

@Composable
private fun ExtensionSourceCard(
    source: ExtensionSourceUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val accent = if (source.enabled) ChatColors.PluginAccent else ChatColors.Secondary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (source.enabled) ChatColors.Accent.copy(alpha = 0.09f) else ChatColors.Elevated,
        shape = RoundedCornerShape(ChatDimensions.CardCorner),
        border = BorderStroke(1.dp, if (source.enabled) ChatColors.Accent.copy(alpha = 0.45f) else ChatColors.Border),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(if (source.isCustom) IconGlyph.GLOBE else IconGlyph.PUZZLE, accent)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source.displayName, fontWeight = FontWeight.SemiBold)
                    if (source.isDefault) {
                        Spacer(Modifier.size(8.dp))
                        Surface(
                            color = ChatColors.Accent.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                "Default",
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                color = ChatColors.Accent,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Text(source.description, color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall)
                Text(source.capabilityLabel, color = ChatColors.Accent, style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = accent, shape = CircleShape, modifier = Modifier.size(7.dp)) {}
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (source.enabled) "Enabled" else "Disabled",
                        color = accent,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Switch(checked = source.enabled, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

@Composable
private fun SourceIcon(glyph: IconGlyph, tint: androidx.compose.ui.graphics.Color) {
    Surface(
        color = ChatColors.ElevatedStrong,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.size(46.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIcon(glyph, Modifier.size(24.dp), tint)
        }
    }
}
