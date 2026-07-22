package io.github.ciurlaro.codexmobile.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.core.AgentCapability
import kotlinx.coroutines.flow.drop

@Composable
internal fun Composer(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shellMode = state.draft.startsWith('!')
    val expanded = focused || state.draft.contains('\n') || state.selectedCapabilities.isNotEmpty()
    val canSend = state.isAuthenticated &&
        if (shellMode) state.draft.drop(1).isNotBlank()
        else state.draft.isNotBlank() || state.selectedCapabilities.isNotEmpty()
    val composerColor by animateColorAsState(
        if (shellMode) Color(0xFF182433) else ChatColors.Elevated,
        label = "composer-mode",
    )
    val composerBorder by animateColorAsState(
        if (shellMode) ChatColors.Accent else ChatColors.Border,
        label = "composer-border",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChatDimensions.ScreenPadding, vertical = 10.dp)
            .background(composerColor, RoundedCornerShape(ChatDimensions.ComposerCorner))
            .border(
                BorderStroke(1.dp, composerBorder),
                RoundedCornerShape(ChatDimensions.ComposerCorner),
            )
            .semantics {
                if (shellMode) stateDescription = "Shell command mode"
            }
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        if (shellMode) {
            Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { ShellHeader() }
        }
        state.selectedCapabilities.forEach { capability ->
            CapabilityChip(capability) { onEvent(ChatUiEvent.RemoveCapability(capability)) }
            Spacer(Modifier.height(4.dp))
        }
        BasicTextField(
            value = state.draft,
            onValueChange = { onEvent(ChatUiEvent.UpdateDraft(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (expanded) 52.dp else 38.dp, max = 160.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = if (shellMode) "Shell command" else "Message"
                },
            enabled = state.isAuthenticated,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = ChatColors.Primary,
                fontFamily = if (shellMode) FontFamily.Monospace else FontFamily.Default,
                fontWeight = if (shellMode) FontWeight.Medium else FontWeight.Normal,
            ),
            cursorBrush = SolidColor(ChatColors.Accent),
            visualTransformation = if (shellMode) {
                shellCommandVisualTransformation
            } else {
                VisualTransformation.None
            },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.TopStart) {
                    if (state.draft.isEmpty()) {
                        Text(
                            when {
                                shellMode -> "Run a shell command"
                                state.messages.isEmpty() -> "Ask Codex"
                                else -> "Reply to Codex"
                            },
                            color = ChatColors.Secondary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(
                label = "Add prompt tag",
                glyph = IconGlyph.PLUS,
                enabled = !shellMode,
                containerColor = Color.Transparent,
            ) { onEvent(ChatUiEvent.OpenSelector(ChatSelector.TAGS)) }
            SelectionPill(
                state = state,
                modifier = Modifier.weight(1f),
                onClick = { onEvent(ChatUiEvent.OpenSelector(ChatSelector.EFFORT)) },
            )
            CircleIconButton(
                label = when {
                    state.isTurnActive -> "Stop response"
                    shellMode -> "Run shell command"
                    else -> "Send message"
                },
                glyph = if (state.isTurnActive) IconGlyph.STOP else IconGlyph.SEND,
                enabled = state.isTurnActive || canSend,
                containerColor = if (state.isTurnActive || canSend) ChatColors.Accent else ChatColors.ElevatedStrong,
            ) {
                onEvent(if (state.isTurnActive) ChatUiEvent.Stop else ChatUiEvent.Send)
            }
        }
    }
}

@Composable
private fun CapabilityChip(
    capability: AgentCapability,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = ChatDimensions.TouchTarget)
            .background(ChatColors.ElevatedStrong, RoundedCornerShape(ChatDimensions.ControlCorner))
            .padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listOfNotNull(capability.icon, capability.displayLabel).joinToString(" "),
            color = ChatColors.Accent,
            style = MaterialTheme.typography.labelLarge,
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(ChatDimensions.TouchTarget)
                .semantics { contentDescription = "Remove ${capability.displayLabel}" },
        ) {
            AppIcon(IconGlyph.CLOSE, Modifier.size(18.dp), ChatColors.Secondary)
        }
    }
}

@Composable
private fun SelectionPill(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = selectionLabel(state),
        modifier = modifier
            .heightIn(min = ChatDimensions.TouchTarget)
            .clip(RoundedCornerShape(ChatDimensions.ControlCorner))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 10.dp, vertical = 14.dp),
        color = ChatColors.Primary,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun selectionLabel(state: MainUiState): String {
    val model = state.models.firstOrNull { it.id == state.selectedModel }
    val modelLabel = model?.displayName ?: state.selectedModel ?: "Model"
    return state.selectedEffort?.let { "$modelLabel · ${effortLabel(it)}" } ?: modelLabel
}
