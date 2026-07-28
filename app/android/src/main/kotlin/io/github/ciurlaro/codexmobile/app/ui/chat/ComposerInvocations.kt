package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.formatting.effortLabel
import io.github.ciurlaro.codexmobile.app.presentation.invocation.PromptInvocation
import io.github.ciurlaro.codexmobile.app.presentation.invocation.suggestedInvocationItems
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.core.AgentCapability

@Composable
internal fun InvocationSuggestions(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEvent(AppUiEvent.AddInvocation(item.invocation)) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
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
internal fun InvocationChip(item: PromptInvocation, onRemove: () -> Unit) {
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
internal fun CapabilityChip(capability: AgentCapability, onRemove: () -> Unit) {
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
            modifier = Modifier.size(ChatDimensions.TouchTarget).semantics {
                contentDescription = "Remove ${capability.displayLabel}"
            },
        ) { AppIcon(IconGlyph.CLOSE, Modifier.size(18.dp), ChatColors.Secondary) }
    }
}

@Composable
internal fun SelectionPill(
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
