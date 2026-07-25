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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.invocation.readableTitle
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionFilter
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSection
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentSkillScope

@Composable
internal fun ExtensionsScreen(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    var showGitHubDialog by remember { mutableStateOf(false) }
    var showPluginSourceDialog by remember { mutableStateOf(false) }
    var pluginSourceSubmissionStarted by remember(showPluginSourceDialog) { mutableStateOf(false) }
    LaunchedEffect(state.selectedSkillPackage) {
        if (state.selectedSkillPackage != null) showGitHubDialog = false
    }
    when {
        state.selectedSkill != null -> SkillDetailScreen(state.selectedSkill, state, onEvent)
        state.selectedSkillPackage != null -> SkillPackageDetailScreen(state.selectedSkillPackage, state, onEvent)
        state.selectedPlugin != null -> PluginDetailScreen(state.selectedPlugin, state, onEvent)
        else -> ExtensionCatalog(
            state,
            onEvent,
            onInstallFromGitHub = {
                onEvent(AppUiEvent.DismissGitHubSkillImport)
                showGitHubDialog = true
            },
            onAddPluginSource = {
                onEvent(AppUiEvent.DismissPluginSource)
                showPluginSourceDialog = true
            },
        )
    }
    state.pendingExtensionRemoval?.let { removal ->
        AlertDialog(
            onDismissRequest = { onEvent(AppUiEvent.DismissExtensionRemoval) },
            title = { Text("Uninstall ${removal.displayName}?") },
            text = { Text("This removes it from Codex Mobile. You can install it again later if it remains available.") },
            confirmButton = {
                TextButton(onClick = { onEvent(AppUiEvent.ConfirmExtensionRemoval) }) {
                    Text("Uninstall", color = ChatColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(AppUiEvent.DismissExtensionRemoval) }) { Text("Cancel") }
            },
        )
    }
    if (showGitHubDialog) {
        GitHubSkillDialog(
            state = state,
            onEvent = onEvent,
            onDismiss = {
                showGitHubDialog = false
                onEvent(AppUiEvent.DismissGitHubSkillImport)
            },
        )
    }
    if (showPluginSourceDialog) {
        PluginSourceDialog(
            state = state,
            onAdd = { onEvent(AppUiEvent.AddPluginSource(it)) },
            onDismiss = {
                showPluginSourceDialog = false
                onEvent(AppUiEvent.DismissPluginSource)
            },
        )
        LaunchedEffect(state.isPluginSourceLoading, state.pluginSourceError) {
            if (state.isPluginSourceLoading) pluginSourceSubmissionStarted = true
            if (
                pluginSourceSubmissionStarted &&
                !state.isPluginSourceLoading &&
                state.pluginSourceError == null
            ) {
                showPluginSourceDialog = false
            }
        }
    }
}

@Composable
private fun PluginSourceDialog(state: AppUiState, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(DEFAULT_PLUGIN_SOURCE) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add plugin source") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Public GitHub marketplace repository") },
                    supportingText = { Text(state.pluginSourceError ?: "https://github.com/owner/repository") },
                    isError = state.pluginSourceError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isPluginSourceLoading) ExtensionLoading("Adding source…")
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !state.isPluginSourceLoading,
                onClick = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onAdd(url)
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val DEFAULT_PLUGIN_SOURCE = "https://github.com/ciurlaro/codex-mobile-plugins"

@Composable
private fun GitHubSkillDialog(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var submittedUrl by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val error = state.githubSkillError.takeIf { submittedUrl == url }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (state.githubSkillCandidates.isEmpty()) "Install skill from GitHub" else "Choose a skill")
        },
        text = {
            if (state.githubSkillCandidates.isEmpty()) {
                Column {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Public GitHub repository or skill URL") },
                        supportingText = {
                            Text(error ?: "https://github.com/owner/repository")
                        },
                        isError = error != null,
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.isGitHubSkillLoading) ExtensionLoading("Finding skills…")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.githubSkillCandidates, key = AgentSkillPackage::id) { skill ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onEvent(AppUiEvent.SelectGitHubSkill(skill))
                            },
                            color = ChatColors.ElevatedStrong,
                            shape = RoundedCornerShape(ChatDimensions.ControlCorner),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(skill.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    skill.sourceUrl.substringAfter("/tree/")
                                        .substringAfter('/', "Repository root"),
                                    color = ChatColors.Secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.githubSkillCandidates.isEmpty()) {
                TextButton(
                    enabled = url.isNotBlank() && !state.isGitHubSkillLoading,
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        submittedUrl = url
                        onEvent(AppUiEvent.OpenGitHubSkill(url))
                    },
                ) { Text("Preview") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExtensionCatalog(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
    onInstallFromGitHub: () -> Unit,
    onAddPluginSource: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        ExtensionTopBar("Extensions") { onEvent(AppUiEvent.CloseExtensions) }
        ExtensionSectionTabs(state.extensionSection) {
            onEvent(AppUiEvent.SelectExtensionSection(it))
        }
        ExtensionFilterChips(state.extensionFilter) {
            onEvent(AppUiEvent.SelectExtensionFilter(it))
        }
        TextField(
            value = state.extensionSearch,
            onValueChange = { onEvent(AppUiEvent.SearchExtensions(it)) },
            placeholder = { Text("Search extensions") },
            leadingIcon = { AppIcon(IconGlyph.SEARCH, Modifier.size(20.dp), ChatColors.Secondary) },
            singleLine = true,
            shape = RoundedCornerShape(ChatDimensions.ControlCorner),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ChatColors.Elevated,
                unfocusedContainerColor = ChatColors.Elevated,
                focusedIndicatorColor = ChatColors.Accent,
                unfocusedIndicatorColor = ChatColors.Border,
            ),
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = ChatDimensions.ScreenPadding,
                vertical = 8.dp,
            ),
        )
        if (state.pluginChangesNeedNewChat) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ChatDimensions.ScreenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Plugin changes are available in a new chat", Modifier.weight(1f))
                Button(onClick = { onEvent(AppUiEvent.StartNewChat) }) { Text("New chat") }
            }
        }
        ExtensionList(state, onEvent, onInstallFromGitHub, onAddPluginSource)
    }
}

@Composable
private fun ExtensionSectionTabs(selected: ExtensionSection, onSelect: (ExtensionSection) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = ChatDimensions.ScreenPadding)) {
        ExtensionSection.entries.forEach { section ->
            Column(
                Modifier.weight(1f).clickable { onSelect(section) }.padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    section.label,
                    color = if (selected == section) ChatColors.Primary else ChatColors.Secondary,
                    fontWeight = if (selected == section) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                Surface(
                    color = if (selected == section) ChatColors.Accent else ChatColors.Border,
                    modifier = Modifier.fillMaxWidth().height(if (selected == section) 3.dp else 1.dp),
                ) {}
            }
        }
    }
}

@Composable
private fun ExtensionFilterChips(selected: ExtensionFilter, onSelect: (ExtensionFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ChatDimensions.ScreenPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExtensionFilter.entries.forEach { filter ->
            val active = selected == filter
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(filter) },
                color = if (active) ChatColors.Accent.copy(alpha = 0.16f) else ChatColors.Elevated,
                shape = RoundedCornerShape(ChatDimensions.ControlCorner),
                border = BorderStroke(1.dp, if (active) ChatColors.Accent else ChatColors.Border),
            ) {
                Text(
                    filter.label,
                    color = if (active) ChatColors.Accent else ChatColors.Secondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ExtensionList(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
    onInstallFromGitHub: () -> Unit,
    onAddPluginSource: () -> Unit,
) {
    val query = state.extensionSearch.trim()
    val installed = state.extensionSection == ExtensionSection.INSTALLED
    val showSkills = state.extensionFilter != ExtensionFilter.PLUGINS
    val showPlugins = state.extensionFilter != ExtensionFilter.SKILLS
    val installedSkills = state.skills
        .filterNot { it.scope == AgentSkillScope.PLUGIN }
        .filter { query.isEmpty() || it.displayName.contains(query, true) || it.description.contains(query, true) }
    val availableSkills = state.availableSkills.filter {
        query.isEmpty() || it.displayName.contains(query, true) || it.description.contains(query, true)
    }
    val installedIds = state.installedPlugins.map { it.reference.id }.toSet()
    val plugins = (if (installed) {
        state.installedPlugins
    } else {
        state.availablePlugins.filterNot { it.reference.id in installedIds }
    }).filter {
        query.isEmpty() || it.displayName.contains(query, true) || it.description.contains(query, true)
    }
    val skillsLoading = showSkills && if (installed) state.isSkillsLoading else state.isAvailableSkillsLoading
    val pluginsLoading = showPlugins && if (installed) state.isInstalledPluginsLoading else state.isAvailablePluginsLoading
    val skillsLoaded = !showSkills || if (installed) state.skillsLoaded else state.availableSkillsLoaded
    val pluginsLoaded = !showPlugins || if (installed) state.installedPluginsLoaded else state.availablePluginsLoaded
    val errors = buildList {
        if (showSkills) add(if (installed) state.skillsError else state.availableSkillsError)
        if (showPlugins) add(if (installed) state.installedPluginsError else state.availablePluginsError)
    }.filterNotNull().distinct()
    val skillsEmpty = if (installed) installedSkills.isEmpty() else availableSkills.isEmpty()
    val empty = (!showSkills || skillsEmpty) && (!showPlugins || plugins.isEmpty())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ChatDimensions.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (errors.isNotEmpty()) {
            item("extension-error") { ExtensionError(errors.joinToString("\n"), onEvent) }
        }
        if (skillsLoading || pluginsLoading) {
            item("capabilities-loading") {
                ExtensionLoading(if (installed) "Loading extensions…" else "Refreshing catalog…")
            }
        }
        if (!installed && showSkills) {
            item("github") {
                TextButton(onClick = onInstallFromGitHub, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(IconGlyph.PLUS, Modifier.size(18.dp), ChatColors.Accent)
                    Spacer(Modifier.size(8.dp))
                    Text("Install a skill from GitHub")
                }
            }
        }
        if (!installed && showPlugins) {
            item("plugin-source") {
                TextButton(onClick = onAddPluginSource, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(IconGlyph.PLUS, Modifier.size(18.dp), ChatColors.Accent)
                    Spacer(Modifier.size(8.dp))
                    Text("Add a plugin source from GitHub")
                }
            }
        }
        if (showSkills && !skillsEmpty) {
            if (showPlugins) item("skills-title") { SectionTitle("Skills") }
            if (installed) {
                items(installedSkills, key = AgentSkill::path) { skill ->
                    ExtensionCard(
                        title = skill.readableTitle(),
                        subtitle = skill.description,
                        metadata = skill.scope.displayName,
                        glyph = IconGlyph.INTELLIGENCE,
                        enabled = skill.enabled,
                        busy = state.extensionOperationId == "skill:${skill.path}",
                        error = state.actionError("skill:${skill.path}"),
                        onClick = { onEvent(AppUiEvent.OpenSkill(skill)) },
                        onToggle = { onEvent(AppUiEvent.ToggleSkill(skill.path, it)) },
                        controlsEnabled = !state.isExtensionMutationLoading,
                    )
                }
            } else {
                items(availableSkills, key = AgentSkillPackage::id) { skill ->
                    ExtensionCard(
                        title = skill.displayName,
                        subtitle = skill.description,
                        metadata = skill.source.displayName,
                        glyph = IconGlyph.INTELLIGENCE,
                        busy = state.extensionOperationId == "skill:${skill.id}",
                        error = state.actionError("skill:${skill.id}"),
                        onClick = { onEvent(AppUiEvent.OpenSkillPackage(skill)) },
                        actionLabel = "Install",
                        onAction = { onEvent(AppUiEvent.InstallSkill(skill)) },
                        controlsEnabled = !state.isExtensionMutationLoading,
                    )
                }
            }
        }
        if (showPlugins && plugins.isNotEmpty()) {
            if (showSkills) item("plugins-title") { SectionTitle("Plugins") }
            items(plugins, key = { it.reference.id }) { plugin -> PluginCard(plugin, state, onEvent) }
        }
        if (empty && skillsLoaded && pluginsLoaded && !skillsLoading && !pluginsLoading && errors.isEmpty()) {
            item("no-capabilities") {
                EmptyCapabilities(
                    if (query.isNotEmpty()) "No extensions match “$query”"
                    else if (installed) "No installed extensions" else "No extensions available right now",
                )
            }
        }
    }
}

@Composable
private fun PluginCard(plugin: AgentPluginSummary, state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    val operationId = "plugin:${plugin.reference.id}"
    val unavailable = plugin.reference.id in state.unavailablePluginIds
    ExtensionCard(
        title = plugin.displayName,
        subtitle = plugin.description.ifBlank { plugin.capabilities.joinToString() },
        metadata = plugin.reference.marketplaceName.replace('-', ' '),
        glyph = IconGlyph.PUZZLE,
        enabled = plugin.enabled,
        busy = state.extensionOperationId == operationId,
        error = state.actionError(operationId),
        onClick = { onEvent(AppUiEvent.OpenPlugin(plugin.reference)) }.takeUnless { unavailable },
        onToggle = if (state.extensionSection == ExtensionSection.INSTALLED) {
            { enabled -> onEvent(AppUiEvent.TogglePlugin(plugin.reference.id, enabled)) }
        } else null,
        actionLabel = (if (unavailable) "Unavailable" else "Install")
            .takeIf { state.extensionSection == ExtensionSection.DISCOVER },
        onAction = { onEvent(AppUiEvent.InstallPlugin(plugin.reference)) }
            .takeIf { state.extensionSection == ExtensionSection.DISCOVER },
        controlsEnabled = !state.isExtensionMutationLoading && plugin.available && !unavailable,
    )
}

@Composable
private fun ExtensionCard(
    title: String,
    subtitle: String,
    metadata: String? = null,
    glyph: IconGlyph,
    enabled: Boolean = true,
    busy: Boolean = false,
    error: String? = null,
    onClick: (() -> Unit)? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    controlsEnabled: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        color = ChatColors.Elevated,
        shape = RoundedCornerShape(ChatDimensions.CardCorner),
        border = BorderStroke(1.dp, ChatColors.Border),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = ChatColors.ElevatedStrong,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(glyph, Modifier.size(21.dp), ChatColors.Primary)
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                metadata?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it.replaceFirstChar(Char::uppercase),
                        color = ChatColors.Accent,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = ChatColors.Secondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = ChatColors.Danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            when {
                busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                onToggle != null -> Switch(checked = enabled, onCheckedChange = onToggle, enabled = controlsEnabled)
                actionLabel != null && onAction != null -> Button(onClick = onAction, enabled = controlsEnabled) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun SkillDetailScreen(skill: AgentSkill, state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        ExtensionTopBar(skill.readableTitle()) { onEvent(AppUiEvent.CloseSkillDetails) }
        LazyColumn(
            contentPadding = PaddingValues(ChatDimensions.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("metadata") {
                Text(skill.description)
                Spacer(Modifier.height(8.dp))
                Text(skill.scope.displayName, color = ChatColors.Secondary)
                SelectionContainer {
                    Text(skill.path, color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
                if (skill.dependencies.isNotEmpty()) {
                    Text("Requires: ${skill.dependencies.joinToString()}", color = ChatColors.Secondary)
                }
            }
            skillSource(state, onEvent)
            state.extensionActionError?.let { error ->
                item("action-error") { ActionError(error.message) }
            }
            if (skill.canUninstall) {
                item("uninstall") {
                    Button(
                        enabled = !state.isExtensionMutationLoading,
                        onClick = { onEvent(AppUiEvent.RequestUninstallSkill(skill)) },
                    ) { Text("Uninstall") }
                }
            }
        }
    }
}

@Composable
private fun SkillPackageDetailScreen(
    skill: AgentSkillPackage,
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        ExtensionTopBar(skill.displayName) { onEvent(AppUiEvent.CloseSkillDetails) }
        LazyColumn(
            contentPadding = PaddingValues(ChatDimensions.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("metadata") {
                Text(skill.description)
                Spacer(Modifier.height(8.dp))
                Text(skill.source.displayName, color = ChatColors.Secondary)
                SelectionContainer {
                    Text(skill.sourceUrl, color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
            skillSource(state, onEvent)
            state.extensionActionError?.let { error ->
                item("action-error") { ActionError(error.message) }
            }
            item("install") {
                Button(
                    enabled = !state.isExtensionMutationLoading,
                    onClick = { onEvent(AppUiEvent.InstallSkill(skill)) },
                ) { Text("Install") }
            }
        }
    }
}

private fun LazyListScope.skillSource(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    item("source-title") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Source")
            Spacer(Modifier.weight(1f))
            if (state.skillSourceTotalBytes > 0) {
                Text("${state.skillSourceTotalBytes} bytes", color = ChatColors.Secondary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    itemsIndexed(state.skillSourceChunks, key = { index, _ -> index }) { _, chunk ->
        SelectionContainer {
            Text(chunk, modifier = Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
        }
    }
    state.skillSourceError?.let { error ->
        item("source-error") { Text(error, color = ChatColors.Danger, style = MaterialTheme.typography.bodySmall) }
    }
    if (state.isSkillSourceLoading) item("source-loading") { ExtensionLoading("Loading source…") }
    if (state.skillSourceNextOffset != null && !state.isSkillSourceLoading) {
        item("load-more") {
            Button(onClick = { onEvent(AppUiEvent.LoadMoreSkillSource) }) { Text("Load more") }
        }
    }
}

@Composable
private fun PluginDetailScreen(detail: AgentPluginDetail, state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        ExtensionTopBar(detail.summary.displayName) { onEvent(AppUiEvent.ClosePluginDetails) }
        LazyColumn(
            contentPadding = PaddingValues(ChatDimensions.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("description") {
                Text(detail.description.ifBlank { detail.summary.description })
                Spacer(Modifier.height(8.dp))
                Text(
                    detail.summary.reference.marketplaceName.replace('-', ' ').replaceFirstChar(Char::uppercase),
                    color = ChatColors.Secondary,
                )
            }
            if (detail.summary.capabilities.isNotEmpty()) item("capabilities") {
                SectionTitle("Capabilities")
                Text(detail.summary.capabilities.joinToString(" · "))
            }
            if (detail.skills.isNotEmpty()) item("skills") {
                SectionTitle("Skills")
                Text(detail.skills.joinToString("\n") {
                    "• ${it.name.substringAfter(':').replace('-', ' ').replaceFirstChar(Char::uppercase)}"
                })
            }
            val visibleMcpServers = detail.mcpServers.takeUnless { detail.providerManaged }.orEmpty()
            if (detail.connectors.isNotEmpty() || visibleMcpServers.isNotEmpty()) item("connectors") {
                SectionTitle("Connectors")
                Text((detail.connectors.map { it.name } + visibleMcpServers).joinToString("\n") { "• $it" })
            }
            if (detail.hookCount > 0) item("hooks") {
                Text("This plugin declares hooks. Codex Mobile does not load or run hooks.", color = ChatColors.Danger)
            }
            state.extensionActionError?.let { error ->
                item("action-error") { ActionError(error.message) }
            }
            listOfNotNull(
                detail.summary.websiteUrl?.let { "Website" to it },
                detail.summary.privacyPolicyUrl?.let { "Privacy policy" to it },
                detail.summary.termsOfServiceUrl?.let { "Terms of service" to it },
            ).takeIf { it.isNotEmpty() }?.let { links ->
                item("links") {
                    SectionTitle("Provider links")
                    links.forEach { (label, url) -> Text("$label: $url", color = ChatColors.Secondary) }
                }
            }
            item("action") {
                if (!detail.summary.installed) {
                    Button(
                        enabled = detail.summary.available && !state.isExtensionMutationLoading,
                        onClick = { onEvent(AppUiEvent.InstallPlugin(detail.summary.reference)) },
                    ) { Text("Install") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (detail.providerManaged) {
                            Button(
                                enabled = !state.isExtensionMutationLoading,
                                onClick = { onEvent(AppUiEvent.InstallPlugin(detail.summary.reference)) },
                            ) { Text("Repair or update") }
                        }
                        TextButton(
                            enabled = !state.isExtensionMutationLoading,
                            onClick = {
                                onEvent(
                                    AppUiEvent.RequestUninstallPlugin(
                                        detail.summary.reference,
                                        detail.summary.displayName,
                                    ),
                                )
                            },
                        ) { Text("Uninstall") }
                    }
                }
                if (!detail.summary.available) {
                    Text("Unavailable under the current installation policy", color = ChatColors.Secondary)
                }
            }
        }
    }
}

@Composable
private fun ExtensionTopBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(ChatDimensions.TopBarHeight).padding(horizontal = ChatDimensions.ScreenPadding)) {
        CircleIconButton("Back", IconGlyph.BACK, Modifier.align(Alignment.CenterStart), onClick = onBack)
        Text(
            title,
            Modifier.align(Alignment.Center).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionTitle(value: String) = Text(
    value,
    color = ChatColors.Secondary,
    style = MaterialTheme.typography.labelLarge,
    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
)

@Composable
private fun EmptyCapabilities(value: String) {
    Text(value, color = ChatColors.Secondary, modifier = Modifier.padding(vertical = 28.dp))
}

@Composable
private fun ExtensionLoading(value: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), color = ChatColors.Accent, strokeWidth = 2.dp)
        value?.let {
            Spacer(Modifier.size(10.dp))
            Text(it, color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ExtensionError(value: String, onEvent: (AppUiEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, color = ChatColors.Danger, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onEvent(AppUiEvent.RefreshExtensions) }) { Text("Retry") }
    }
}

@Composable
private fun ActionError(value: String) {
    Text(value, color = ChatColors.Danger, style = MaterialTheme.typography.bodySmall)
}

private fun AppUiState.actionError(operationId: String): String? =
    extensionActionError?.takeIf { it.operationId == operationId }?.message

private fun Modifier.statusAndNavigationPadding() = statusBarsPadding().navigationBarsPadding()
