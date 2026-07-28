package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.invocation.PromptInvocationKind
import io.github.ciurlaro.codexmobile.app.presentation.invocation.availablePromptInvocations
import io.github.ciurlaro.codexmobile.app.presentation.invocation.recentPromptInvocations
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.core.AgentCapability

@Composable
internal fun TagSelector(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    val tags = remember(state.selectedCapabilities) {
        AgentCapability.entries.filter { it !in state.selectedCapabilities }
    }
    val skills = state.availablePromptInvocations(PromptInvocationKind.SKILL)
    val plugins = state.availablePromptInvocations(PromptInvocationKind.PLUGIN)
    val recent = state.recentPromptInvocations()
    LazyColumn(Modifier.heightIn(max = 500.dp).padding(vertical = 12.dp)) {
        item("prompt-heading") { SelectorSectionTitle("Add to prompt") }
        items(tags, key = AgentCapability::id) { capability ->
            SelectorRow(
                title = capability.displayLabel,
                selected = false,
                leading = IconGlyph.GLOBE,
                leadingTint = ChatColors.Accent,
                onClick = { onEvent(AppUiEvent.AddCapability(capability)) },
            )
        }
        item("skills") {
            SelectorRow(
                title = "Skills",
                subtitle = if (state.isSkillsLoading) "Loading…" else availableLabel(skills.size, "skill"),
                selected = false,
                leading = IconGlyph.SPARKLES,
                leadingTint = ChatColors.SkillAccent,
                trailing = IconGlyph.CHEVRON_RIGHT,
                onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.SKILLS)) },
            )
        }
        item("plugins") {
            SelectorRow(
                title = "Plugins",
                subtitle = if (state.isPluginCatalogLoading) "Loading…" else availableLabel(plugins.size, "plugin"),
                selected = false,
                leading = IconGlyph.PUZZLE,
                leadingTint = ChatColors.PluginAccent,
                trailing = IconGlyph.CHEVRON_RIGHT,
                onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.PLUGINS)) },
            )
        }
        if (recent.isNotEmpty()) {
            item("recent-heading") { SelectorSectionTitle("Recently used") }
            items(recent, key = { "recent-${it.invocation.key}" }) { item ->
                PromptInvocationRow(item) { onEvent(AppUiEvent.AddInvocation(item.invocation)) }
            }
        }
    }
}

@Composable
internal fun PromptInvocationSelector(
    state: AppUiState,
    kind: PromptInvocationKind,
    onEvent: (AppUiEvent) -> Unit,
) {
    var query by rememberSaveable(kind) { mutableStateOf("") }
    val all = state.availablePromptInvocations(kind)
    val matches = all.filter { query.isBlank() || it.searchableText.contains(query.trim(), ignoreCase = true) }
    val recent = if (query.isBlank()) state.recentPromptInvocations(kind) else emptyList()
    val recentKeys = recent.mapTo(mutableSetOf()) { it.invocation.key }
    val remaining = matches.filterNot { it.invocation.key in recentKeys }
    val title = if (kind == PromptInvocationKind.SKILL) "Skills" else "Plugins"
    val singular = if (kind == PromptInvocationKind.SKILL) "skill" else "plugin"
    val loading = if (kind == PromptInvocationKind.SKILL) state.isSkillsLoading else state.isPluginCatalogLoading
    val type = if (kind == PromptInvocationKind.SKILL) ExtensionType.SKILLS else ExtensionType.PLUGINS

    LazyColumn(
        modifier = Modifier.heightIn(min = 500.dp, max = 500.dp).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item("header") { SelectorHeader(title) { onEvent(AppUiEvent.OpenSelector(ChatSelector.TAGS)) } }
        item("search") {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search $title", color = ChatColors.Secondary) },
                leadingIcon = { AppIcon(IconGlyph.SEARCH, Modifier.size(20.dp), ChatColors.Secondary) },
                singleLine = true,
                shape = RoundedCornerShape(ChatDimensions.ControlCorner),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = ChatColors.ElevatedStrong,
                    unfocusedContainerColor = ChatColors.ElevatedStrong,
                    focusedIndicatorColor = ChatColors.Accent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(max = 52.dp),
            )
        }
        if (recent.isNotEmpty()) {
            item("recent-heading") { SelectorSectionTitle("Recently used") }
            items(recent, key = { "recent-${it.invocation.key}" }) { item ->
                PromptInvocationRow(item) { onEvent(AppUiEvent.AddInvocation(item.invocation)) }
            }
        }
        if (remaining.isNotEmpty()) {
            if (recent.isNotEmpty()) item("all-heading") { SelectorSectionTitle("All $title") }
            items(remaining, key = { it.invocation.key }) { item ->
                PromptInvocationRow(item) { onEvent(AppUiEvent.AddInvocation(item.invocation)) }
            }
        } else if (recent.isEmpty()) {
            item("empty") {
                Text(
                    when {
                        loading -> "Loading ${singular}s…"
                        query.isBlank() -> "No enabled ${singular}s"
                        else -> "No $singular matches “${query.trim()}”"
                    },
                    color = ChatColors.Secondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
        item("manage") {
            HorizontalDivider(color = ChatColors.Border, modifier = Modifier.padding(horizontal = 20.dp))
            SelectorRow(
                title = "Manage $title",
                selected = false,
                trailing = IconGlyph.CHEVRON_RIGHT,
                onClick = { onEvent(AppUiEvent.OpenExtensions(type, AppScreen.CHAT)) },
            )
        }
    }
}
