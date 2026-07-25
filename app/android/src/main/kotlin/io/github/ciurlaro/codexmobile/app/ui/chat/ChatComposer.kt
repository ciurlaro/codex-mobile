package io.github.ciurlaro.codexmobile.app.ui.chat

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
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.formatting.effortLabel
import io.github.ciurlaro.codexmobile.app.presentation.invocation.PromptInvocation
import io.github.ciurlaro.codexmobile.app.presentation.invocation.promptInvocation
import io.github.ciurlaro.codexmobile.app.presentation.invocation.suggestedInvocationItems
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentInvocation

@Composable
internal fun ChatComposer(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shellMode = state.draft.startsWith('!')
    val expanded = focused || state.draft.contains('\n') || state.selectedCapabilities.isNotEmpty() ||
        state.selectedInvocations.isNotEmpty()
    val canSend = state.isAuthenticated &&
        if (shellMode) state.draft.drop(1).isNotBlank()
        else state.draft.isNotBlank() || state.selectedCapabilities.isNotEmpty() ||
            state.selectedInvocations.isNotEmpty()
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
            CapabilityChip(capability) { onEvent(AppUiEvent.RemoveCapability(capability)) }
            Spacer(Modifier.height(4.dp))
        }
        state.selectedInvocations.forEach { invocation ->
            InvocationChip(state.promptInvocation(invocation)) {
                onEvent(AppUiEvent.RemoveInvocation(invocation.key))
            }
            Spacer(Modifier.height(4.dp))
        }
        BasicTextField(
            value = state.draft,
            onValueChange = { onEvent(AppUiEvent.UpdateDraft(it)) },
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
        InvocationSuggestions(state, onEvent)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(
                label = "Add prompt tag",
                glyph = IconGlyph.PLUS,
                enabled = !shellMode,
                containerColor = Color.Transparent,
            ) { onEvent(AppUiEvent.OpenSelector(ChatSelector.TAGS)) }
            SelectionPill(
                state = state,
                modifier = Modifier.weight(1f),
                onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.EFFORT)) },
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
                onEvent(if (state.isTurnActive) AppUiEvent.Stop else AppUiEvent.Send)
            }
        }
    }
}

@Composable
private fun InvocationSuggestions(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    val suggestions = state.suggestedInvocationItems()
    if (suggestions.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(ChatColors.ElevatedStrong, RoundedCornerShape(ChatDimensions.ControlCorner))
            .padding(vertical = 4.dp),
    ) {
        suggestions.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    onEvent(AppUiEvent.AddInvocation(item.invocation))
                }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(item.glyph(), Modifier.size(21.dp), item.accent())
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = ChatColors.Primary, style = MaterialTheme.typography.bodyMedium)
                    item.subtitle?.let {
                        Text(
                            it,
                            color = ChatColors.Secondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InvocationChip(item: PromptInvocation, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = ChatDimensions.TouchTarget)
            .background(ChatColors.ElevatedStrong, RoundedCornerShape(ChatDimensions.ControlCorner))
            .padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(item.glyph(), Modifier.size(18.dp), item.accent())
        Spacer(Modifier.size(8.dp))
        Text(item.title, color = ChatColors.Accent, style = MaterialTheme.typography.labelLarge)
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(ChatDimensions.TouchTarget).semantics {
                contentDescription = "Remove ${item.title}"
            },
        ) { AppIcon(IconGlyph.CLOSE, Modifier.size(18.dp), ChatColors.Secondary) }
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
    state: AppUiState,
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

private fun selectionLabel(state: AppUiState): String {
    val model = state.models.firstOrNull { it.id == state.selectedModel }
    val modelLabel = model?.displayName ?: state.selectedModel ?: "Model"
    return state.selectedEffort?.let { "$modelLabel · ${effortLabel(it)}" } ?: modelLabel
}
