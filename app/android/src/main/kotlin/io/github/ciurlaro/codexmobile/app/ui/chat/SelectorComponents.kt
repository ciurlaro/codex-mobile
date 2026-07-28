package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.invocation.PromptInvocation
import io.github.ciurlaro.codexmobile.app.presentation.invocation.PromptInvocationKind
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors

@Composable
internal fun SelectorHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton("Back to prompt items", IconGlyph.BACK, containerColor = Color.Transparent, onClick = onBack)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
internal fun SelectorSectionTitle(title: String) {
    Text(
        title,
        color = ChatColors.Secondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
internal fun PromptInvocationRow(item: PromptInvocation, onClick: () -> Unit) {
    SelectorRow(
        title = item.title,
        subtitle = item.subtitle,
        selected = false,
        leading = if (item.kind == PromptInvocationKind.SKILL) IconGlyph.SPARKLES else IconGlyph.PUZZLE,
        leadingTint = if (item.kind == PromptInvocationKind.SKILL) ChatColors.SkillAccent else ChatColors.PluginAccent,
        onClick = onClick,
    )
}

internal fun availableLabel(count: Int, singular: String): String = when (count) {
    0 -> "No enabled ${singular}s"
    1 -> "1 enabled $singular"
    else -> "$count enabled ${singular}s"
}

@Composable
internal fun SelectorRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    enabled: Boolean = true,
    leading: IconGlyph? = null,
    leadingTint: Color = ChatColors.Primary,
    trailing: IconGlyph? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (selected) stateDescription = "Selected"
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            AppIcon(it, Modifier.size(22.dp), if (enabled) leadingTint else ChatColors.Secondary)
            Spacer(Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) ChatColors.Primary else ChatColors.Secondary,
                style = MaterialTheme.typography.bodyLarge,
            )
            subtitle?.let {
                Text(
                    it,
                    color = ChatColors.Secondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            selected -> AppIcon(IconGlyph.CHECK, Modifier.size(22.dp), ChatColors.Primary)
            trailing != null -> AppIcon(trailing, Modifier.size(20.dp), ChatColors.Primary)
        }
    }
}
