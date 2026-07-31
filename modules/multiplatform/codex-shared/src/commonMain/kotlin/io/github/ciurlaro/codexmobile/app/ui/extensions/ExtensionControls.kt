package io.github.ciurlaro.codexmobile.app.ui.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions

@Composable
internal fun ExtensionTypeControl(selected: ExtensionType, onSelect: (ExtensionType) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ChatDimensions.ScreenPadding, vertical = 8.dp),
        color = ChatColors.ElevatedStrong,
        shape = RoundedCornerShape(ChatDimensions.ControlCorner),
        border = BorderStroke(1.dp, ChatColors.Border),
    ) {
        Row {
            ExtensionType.entries.forEach { type ->
                val active = selected == type
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSelect(type) },
                    color = if (active) ChatColors.Accent.copy(alpha = 0.22f) else ChatColors.ElevatedStrong,
                    shape = RoundedCornerShape(ChatDimensions.ControlCorner),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(
                            if (type == ExtensionType.SKILLS) IconGlyph.INTELLIGENCE else IconGlyph.PUZZLE,
                            Modifier.size(19.dp),
                            if (active) ChatColors.Accent else ChatColors.Secondary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            type.label,
                            color = if (active) ChatColors.Accent else ChatColors.Secondary,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExtensionSearchAndActions(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ChatDimensions.ScreenPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f).height(ChatDimensions.TouchTarget),
            color = ChatColors.Elevated,
            shape = RoundedCornerShape(ChatDimensions.ControlCorner),
            border = BorderStroke(1.dp, ChatColors.Border),
        ) {
            BasicTextField(
                value = state.extensionSearch,
                onValueChange = { onEvent(AppUiEvent.SearchExtensions(it)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ChatColors.Primary),
                cursorBrush = SolidColor(ChatColors.Accent),
                modifier = Modifier.fillMaxSize(),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(IconGlyph.SEARCH, Modifier.size(20.dp), ChatColors.Secondary)
                        Spacer(Modifier.size(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (state.extensionSearch.isEmpty()) {
                                Text(
                                    "Search extensions",
                                    color = ChatColors.Secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            innerTextField()
                        }
                    }
                },
            )
        }
        if (state.extensionType == ExtensionType.PLUGINS) {
            Spacer(Modifier.size(8.dp))
            ExtensionStatusMenu(state.extensionStatus) {
                onEvent(AppUiEvent.SelectExtensionStatus(it))
            }
        }
    }
}

@Composable
private fun ExtensionStatusMenu(
    selected: ExtensionStatus,
    onSelect: (ExtensionStatus) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier.height(ChatDimensions.TouchTarget).widthIn(min = 104.dp).clickable { expanded = true },
            color = ChatColors.Elevated,
            shape = RoundedCornerShape(ChatDimensions.ControlCorner),
            border = BorderStroke(1.dp, ChatColors.Border),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                AppIcon(IconGlyph.FILTER_SLIDERS, Modifier.size(19.dp), ChatColors.Primary)
                Spacer(Modifier.size(7.dp))
                Text(selected.label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ExtensionStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.label) },
                    leadingIcon = {
                        if (status == selected) AppIcon(IconGlyph.CHECK, Modifier.size(18.dp), ChatColors.Accent)
                    },
                    onClick = {
                        expanded = false
                        onSelect(status)
                    },
                )
            }
        }
    }
}
