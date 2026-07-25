package io.github.ciurlaro.codexmobile.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.formatting.effortLabel
import io.github.ciurlaro.codexmobile.app.presentation.invocation.PromptInvocation
import io.github.ciurlaro.codexmobile.app.presentation.invocation.PromptInvocationKind
import io.github.ciurlaro.codexmobile.app.presentation.invocation.availablePromptInvocations
import io.github.ciurlaro.codexmobile.app.presentation.invocation.recentPromptInvocations
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionFilter
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.state.selectedModelOrNull
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentInvocation

@Composable
internal fun ChatSelectorOverlay(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
    aboveComposer: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.activeSelector) {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onEvent(AppUiEvent.DismissSelector) },
            )
            .semantics {
                contentDescription = "Dismiss selector"
                role = Role.Button
            },
    ) {
        Surface(
            modifier = Modifier
                .align(if (aboveComposer) Alignment.BottomEnd else Alignment.Center)
                .statusBarsPadding()
                .navigationBarsPadding()
                .then(if (aboveComposer) Modifier.imePadding() else Modifier)
                .padding(
                    end = ChatDimensions.ScreenPadding,
                    start = ChatDimensions.ScreenPadding,
                    bottom = if (aboveComposer) ChatDimensions.SelectorBottomOffset else 0.dp,
                )
                .widthIn(max = ChatDimensions.SelectorWidth)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            shape = RoundedCornerShape(ChatDimensions.CardCorner),
            color = ChatColors.Elevated,
            contentColor = ChatColors.Primary,
            border = BorderStroke(1.dp, ChatColors.Border),
            tonalElevation = 8.dp,
        ) {
            when (state.activeSelector) {
                ChatSelector.EFFORT -> EffortSelector(state, onEvent)
                ChatSelector.MODEL -> ModelSelector(state, onEvent)
                ChatSelector.TAGS -> TagSelector(state, onEvent)
                ChatSelector.SKILLS -> PromptInvocationSelector(state, PromptInvocationKind.SKILL, onEvent)
                ChatSelector.PLUGINS -> PromptInvocationSelector(state, PromptInvocationKind.PLUGIN, onEvent)
                ChatSelector.SPEED -> SpeedSelector(state, onEvent)
                ChatSelector.APPROVAL -> ApprovalSelector(state, onEvent)
                null -> Unit
            }
        }
    }
}

@Composable
private fun EffortSelector(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    val model = state.models.firstOrNull { it.id == state.selectedModel }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        SelectorRow(
            title = "Model",
            subtitle = model?.displayName ?: state.selectedModel ?: "Unavailable",
            selected = false,
            trailing = IconGlyph.CHEVRON_RIGHT,
            onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.MODEL)) },
        )
        HorizontalDivider(color = ChatColors.Border, modifier = Modifier.padding(horizontal = 20.dp))
        SelectorRow(
            title = "Speed",
            subtitle = model?.serviceTiers?.firstOrNull { it.id == state.selectedSpeedTier }?.name
                ?: "Default",
            selected = false,
            trailing = IconGlyph.CHEVRON_RIGHT,
            onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.SPEED)) },
        )
        HorizontalDivider(color = ChatColors.Border, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            "Intelligence",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
        )
        if (model == null || model.supportedEfforts.isEmpty()) {
            Text(
                "Effort options load after sign-in",
                color = ChatColors.Secondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        } else {
            model.supportedEfforts.forEach { effort ->
                SelectorRow(
                    title = effortLabel(effort),
                    selected = effort == state.selectedEffort,
                    onClick = { onEvent(AppUiEvent.SelectEffort(effort)) },
                )
            }
        }
    }
}

@Composable
private fun ModelSelector(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    LazyColumn(Modifier.padding(vertical = 12.dp)) {
        item("model-heading") {
            Text(
                "Model",
                color = ChatColors.Secondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        items(state.models, key = AgentModel::id) { model ->
            SelectorRow(
                title = model.displayName,
                subtitle = model.description.takeIf(String::isNotBlank),
                selected = model.id == state.selectedModel,
                onClick = { onEvent(AppUiEvent.SelectModel(model.id)) },
            )
        }
        if (state.models.isEmpty()) {
            item("models-unavailable") {
                Text(
                    "Models load after sign-in",
                    color = ChatColors.Secondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SpeedSelector(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    val model = state.selectedModelOrNull()
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Text(
            "Speed",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        SelectorRow(
            title = "Default",
            subtitle = "Use the model's default service tier",
            selected = state.selectedSpeedTier == null,
            onClick = { onEvent(AppUiEvent.SelectSpeed(null)) },
        )
        model?.serviceTiers.orEmpty().forEach { tier ->
            SelectorRow(
                title = tier.name,
                subtitle = tier.description.takeIf(String::isNotBlank),
                selected = tier.id == state.selectedSpeedTier,
                onClick = { onEvent(AppUiEvent.SelectSpeed(tier.id)) },
            )
        }
    }
}

@Composable
private fun ApprovalSelector(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Text(
            "Approvals",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        AgentApprovalPreset.entries.forEach { preset ->
            SelectorRow(
                title = preset.displayName,
                subtitle = when (preset) {
                    AgentApprovalPreset.NEVER -> "Run without asking (default)"
                    AgentApprovalPreset.AUTO_REVIEW -> "Let the model review risky actions"
                    AgentApprovalPreset.ASK_ME -> "Ask when Codex requests permission"
                    AgentApprovalPreset.STRICT -> "Ask for commands outside the trusted set"
                },
                selected = preset == state.approvalPreset,
                onClick = { onEvent(AppUiEvent.SelectApproval(preset)) },
            )
        }
    }
}

@Composable
private fun TagSelector(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    val tags = remember(state.selectedCapabilities) {
        AgentCapability.entries.filter { it !in state.selectedCapabilities }
    }
    val skills = state.availablePromptInvocations(PromptInvocationKind.SKILL)
    val plugins = state.availablePromptInvocations(PromptInvocationKind.PLUGIN)
    val recent = state.recentPromptInvocations()
    LazyColumn(
        Modifier
            .heightIn(max = 500.dp)
            .padding(vertical = 12.dp),
    ) {
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
                subtitle = if (state.isInstalledPluginsLoading) "Loading…" else availableLabel(plugins.size, "plugin"),
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
private fun PromptInvocationSelector(
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
    val loading = if (kind == PromptInvocationKind.SKILL) state.isSkillsLoading else state.isInstalledPluginsLoading
    val filter = if (kind == PromptInvocationKind.SKILL) ExtensionFilter.SKILLS else ExtensionFilter.PLUGINS

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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .heightIn(max = 52.dp),
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
                onClick = {
                    onEvent(AppUiEvent.OpenExtensions(filter = filter, returnScreen = AppScreen.CHAT))
                },
            )
        }
    }
}

@Composable
private fun SelectorHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton("Back to prompt items", IconGlyph.BACK, containerColor = Color.Transparent, onClick = onBack)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun SelectorSectionTitle(title: String) {
    Text(
        title,
        color = ChatColors.Secondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun PromptInvocationRow(item: PromptInvocation, onClick: () -> Unit) {
    SelectorRow(
        title = item.title,
        subtitle = item.subtitle,
        selected = false,
        leading = if (item.kind == PromptInvocationKind.SKILL) IconGlyph.SPARKLES else IconGlyph.PUZZLE,
        leadingTint = if (item.kind == PromptInvocationKind.SKILL) ChatColors.SkillAccent else ChatColors.PluginAccent,
        onClick = onClick,
    )
}

private fun availableLabel(count: Int, singular: String): String = when (count) {
    0 -> "No enabled ${singular}s"
    1 -> "1 enabled $singular"
    else -> "$count enabled ${singular}s"
}

@Composable
private fun SelectorRow(
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
