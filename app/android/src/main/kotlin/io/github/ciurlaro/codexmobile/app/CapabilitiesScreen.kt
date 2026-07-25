package io.github.ciurlaro.codexmobile.app

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
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentSkillScope

@Composable
internal fun CapabilitiesScreen(state: MainUiState, onEvent: (ChatUiEvent) -> Unit) {
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
        else -> CapabilityCatalog(
            state,
            onEvent,
            onInstallFromGitHub = {
                onEvent(ChatUiEvent.DismissGitHubSkillImport)
                showGitHubDialog = true
            },
            onAddPluginSource = {
                onEvent(ChatUiEvent.DismissPluginSource)
                showPluginSourceDialog = true
            },
        )
    }
    state.pendingCapabilityRemoval?.let { removal ->
        AlertDialog(
            onDismissRequest = { onEvent(ChatUiEvent.DismissCapabilityRemoval) },
            title = { Text("Uninstall ${removal.displayName}?") },
            text = { Text("This removes it from Codex Mobile. You can install it again later if it remains available.") },
            confirmButton = {
                TextButton(onClick = { onEvent(ChatUiEvent.ConfirmCapabilityRemoval) }) {
                    Text("Uninstall", color = ChatColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(ChatUiEvent.DismissCapabilityRemoval) }) { Text("Cancel") }
            },
        )
    }
    if (showGitHubDialog) {
        GitHubSkillDialog(
            state = state,
            onEvent = onEvent,
            onDismiss = {
                showGitHubDialog = false
                onEvent(ChatUiEvent.DismissGitHubSkillImport)
            },
        )
    }
    if (showPluginSourceDialog) {
        PluginSourceDialog(
            state = state,
            onAdd = { onEvent(ChatUiEvent.AddPluginSource(it)) },
            onDismiss = {
                showPluginSourceDialog = false
                onEvent(ChatUiEvent.DismissPluginSource)
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
private fun PluginSourceDialog(state: MainUiState, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
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
                if (state.isPluginSourceLoading) CapabilityLoading("Adding source…")
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
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
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
                    if (state.isGitHubSkillLoading) CapabilityLoading("Finding skills…")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.githubSkillCandidates, key = AgentSkillPackage::id) { skill ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onEvent(ChatUiEvent.SelectGitHubSkill(skill))
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
                        onEvent(ChatUiEvent.OpenGitHubSkill(url))
                    },
                ) { Text("Preview") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CapabilityCatalog(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onInstallFromGitHub: () -> Unit,
    onAddPluginSource: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        CapabilityTopBar("Extensions") { onEvent(ChatUiEvent.CloseCapabilities) }
        CapabilitySectionTabs(state.capabilitySection) {
            onEvent(ChatUiEvent.SelectCapabilitySection(it))
        }
        CapabilityFilterChips(state.capabilityFilter) {
            onEvent(ChatUiEvent.SelectCapabilityFilter(it))
        }
        TextField(
            value = state.capabilitySearch,
            onValueChange = { onEvent(ChatUiEvent.SearchCapabilities(it)) },
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
                Button(onClick = { onEvent(ChatUiEvent.StartNewChat) }) { Text("New chat") }
            }
        }
        CapabilityList(state, onEvent, onInstallFromGitHub, onAddPluginSource)
    }
}

@Composable
private fun CapabilitySectionTabs(selected: CapabilitySection, onSelect: (CapabilitySection) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = ChatDimensions.ScreenPadding)) {
        CapabilitySection.entries.forEach { section ->
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
private fun CapabilityFilterChips(selected: CapabilityFilter, onSelect: (CapabilityFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ChatDimensions.ScreenPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapabilityFilter.entries.forEach { filter ->
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
private fun CapabilityList(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
    onInstallFromGitHub: () -> Unit,
    onAddPluginSource: () -> Unit,
) {
    val query = state.capabilitySearch.trim()
    val installed = state.capabilitySection == CapabilitySection.INSTALLED
    val showSkills = state.capabilityFilter != CapabilityFilter.PLUGINS
    val showPlugins = state.capabilityFilter != CapabilityFilter.SKILLS
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
            item("capability-error") { CapabilityError(errors.joinToString("\n"), onEvent) }
        }
        if (skillsLoading || pluginsLoading) {
            item("capabilities-loading") {
                CapabilityLoading(if (installed) "Loading extensions…" else "Refreshing catalog…")
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
                    CapabilityCard(
                        title = skill.readableTitle(),
                        subtitle = skill.description,
                        metadata = skill.scope.displayName,
                        glyph = IconGlyph.INTELLIGENCE,
                        enabled = skill.enabled,
                        busy = state.capabilityOperationId == "skill:${skill.path}",
                        error = state.actionError("skill:${skill.path}"),
                        onClick = { onEvent(ChatUiEvent.OpenSkill(skill)) },
                        onToggle = { onEvent(ChatUiEvent.ToggleSkill(skill.path, it)) },
                        controlsEnabled = !state.isCapabilityMutationLoading,
                    )
                }
            } else {
                items(availableSkills, key = AgentSkillPackage::id) { skill ->
                    CapabilityCard(
                        title = skill.displayName,
                        subtitle = skill.description,
                        metadata = skill.source.displayName,
                        glyph = IconGlyph.INTELLIGENCE,
                        busy = state.capabilityOperationId == "skill:${skill.id}",
                        error = state.actionError("skill:${skill.id}"),
                        onClick = { onEvent(ChatUiEvent.OpenSkillPackage(skill)) },
                        actionLabel = "Install",
                        onAction = { onEvent(ChatUiEvent.InstallSkill(skill)) },
                        controlsEnabled = !state.isCapabilityMutationLoading,
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
private fun PluginCard(plugin: AgentPluginSummary, state: MainUiState, onEvent: (ChatUiEvent) -> Unit) {
    val operationId = "plugin:${plugin.reference.id}"
    val unavailable = plugin.reference.id in state.unavailablePluginIds
    CapabilityCard(
        title = plugin.displayName,
        subtitle = plugin.description.ifBlank { plugin.capabilities.joinToString() },
        metadata = plugin.reference.marketplaceName.replace('-', ' '),
        glyph = IconGlyph.PUZZLE,
        enabled = plugin.enabled,
        busy = state.capabilityOperationId == operationId,
        error = state.actionError(operationId),
        onClick = { onEvent(ChatUiEvent.OpenPlugin(plugin.reference)) }.takeUnless { unavailable },
        onToggle = if (state.capabilitySection == CapabilitySection.INSTALLED) {
            { enabled -> onEvent(ChatUiEvent.TogglePlugin(plugin.reference.id, enabled)) }
        } else null,
        actionLabel = (if (unavailable) "Unavailable" else "Install")
            .takeIf { state.capabilitySection == CapabilitySection.DISCOVER },
        onAction = { onEvent(ChatUiEvent.InstallPlugin(plugin.reference)) }
            .takeIf { state.capabilitySection == CapabilitySection.DISCOVER },
        controlsEnabled = !state.isCapabilityMutationLoading && plugin.available && !unavailable,
    )
}

@Composable
private fun CapabilityCard(
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
private fun SkillDetailScreen(skill: AgentSkill, state: MainUiState, onEvent: (ChatUiEvent) -> Unit) {
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        CapabilityTopBar(skill.readableTitle()) { onEvent(ChatUiEvent.CloseSkillDetails) }
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
            state.capabilityActionError?.let { error ->
                item("action-error") { ActionError(error.message) }
            }
            if (skill.canUninstall) {
                item("uninstall") {
                    Button(
                        enabled = !state.isCapabilityMutationLoading,
                        onClick = { onEvent(ChatUiEvent.RequestUninstallSkill(skill)) },
                    ) { Text("Uninstall") }
                }
            }
        }
    }
}

@Composable
private fun SkillPackageDetailScreen(
    skill: AgentSkillPackage,
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        CapabilityTopBar(skill.displayName) { onEvent(ChatUiEvent.CloseSkillDetails) }
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
            state.capabilityActionError?.let { error ->
                item("action-error") { ActionError(error.message) }
            }
            item("install") {
                Button(
                    enabled = !state.isCapabilityMutationLoading,
                    onClick = { onEvent(ChatUiEvent.InstallSkill(skill)) },
                ) { Text("Install") }
            }
        }
    }
}

private fun LazyListScope.skillSource(state: MainUiState, onEvent: (ChatUiEvent) -> Unit) {
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
    if (state.isSkillSourceLoading) item("source-loading") { CapabilityLoading("Loading source…") }
    if (state.skillSourceNextOffset != null && !state.isSkillSourceLoading) {
        item("load-more") {
            Button(onClick = { onEvent(ChatUiEvent.LoadMoreSkillSource) }) { Text("Load more") }
        }
    }
}

@Composable
private fun PluginDetailScreen(detail: AgentPluginDetail, state: MainUiState, onEvent: (ChatUiEvent) -> Unit) {
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        CapabilityTopBar(detail.summary.displayName) { onEvent(ChatUiEvent.ClosePluginDetails) }
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
            state.capabilityActionError?.let { error ->
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
                        enabled = detail.summary.available && !state.isCapabilityMutationLoading,
                        onClick = { onEvent(ChatUiEvent.InstallPlugin(detail.summary.reference)) },
                    ) { Text("Install") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (detail.providerManaged) {
                            Button(
                                enabled = !state.isCapabilityMutationLoading,
                                onClick = { onEvent(ChatUiEvent.InstallPlugin(detail.summary.reference)) },
                            ) { Text("Repair or update") }
                        }
                        TextButton(
                            enabled = !state.isCapabilityMutationLoading,
                            onClick = {
                                onEvent(
                                    ChatUiEvent.RequestUninstallPlugin(
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
private fun CapabilityTopBar(title: String, onBack: () -> Unit) {
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
private fun CapabilityLoading(value: String? = null) {
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
private fun CapabilityError(value: String, onEvent: (ChatUiEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, color = ChatColors.Danger, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onEvent(ChatUiEvent.RefreshCapabilities) }) { Text("Retry") }
    }
}

@Composable
private fun ActionError(value: String) {
    Text(value, color = ChatColors.Danger, style = MaterialTheme.typography.bodySmall)
}

private fun MainUiState.actionError(operationId: String): String? =
    capabilityActionError?.takeIf { it.operationId == operationId }?.message

private fun Modifier.statusAndNavigationPadding() = statusBarsPadding().navigationBarsPadding()
