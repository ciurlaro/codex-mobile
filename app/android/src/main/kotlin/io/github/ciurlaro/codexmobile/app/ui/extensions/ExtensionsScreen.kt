package io.github.ciurlaro.codexmobile.app.ui.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.invocation.readableTitle
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.uninstalledStatus
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentSkillScope
import kotlin.math.ceil
import kotlin.math.max

@Composable
internal fun ExtensionsScreen(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    var showSourceDialog by remember { mutableStateOf(false) }
    var sourceSubmissionStarted by remember(showSourceDialog) { mutableStateOf(false) }
    if (state.extensionSourcesOpen) {
        ExtensionSourcesScreen(
            state = state,
            onEvent = onEvent,
            onAddSource = {
                onEvent(AppUiEvent.DismissExtensionSource)
                showSourceDialog = true
            },
        )
    } else {
        ExtensionCatalog(state, onEvent)
    }
    state.pendingExtensionRemoval?.let { removal ->
        AlertDialog(
            onDismissRequest = { onEvent(AppUiEvent.DismissExtensionRemoval) },
            title = { Text("Uninstall ${removal.displayName}?") },
            text = { Text("This removes it from Codex Mobile. You can install it again while its source is available.") },
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
    if (showSourceDialog) {
        ExtensionSourceDialog(
            state = state,
            onAdd = { onEvent(AppUiEvent.AddExtensionSource(it)) },
            onDismiss = {
                showSourceDialog = false
                onEvent(AppUiEvent.DismissExtensionSource)
            },
        )
        LaunchedEffect(state.isExtensionSourceLoading, state.extensionSourceError) {
            if (state.isExtensionSourceLoading) sourceSubmissionStarted = true
            if (sourceSubmissionStarted && !state.isExtensionSourceLoading && state.extensionSourceError == null) {
                showSourceDialog = false
            }
        }
    }
}

@Composable
private fun ExtensionSourceDialog(state: AppUiState, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add extension source") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Public GitHub repository") },
                    supportingText = {
                        Text(state.extensionSourceError ?: "The repository may contain skills, plugins, or both")
                    },
                    isError = state.extensionSourceError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isExtensionSourceLoading) ExtensionLoading("Checking for skills and plugins…")
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !state.isExtensionSourceLoading,
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

@Composable
private fun ExtensionCatalog(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        ExtensionTopBar("Extensions") { onEvent(AppUiEvent.CloseExtensions) }
        ExtensionTypeControl(state.extensionType) { onEvent(AppUiEvent.SelectExtensionType(it)) }
        ExtensionSearchAndActions(state, onEvent)
        ExtensionResults(state, onEvent, Modifier.weight(1f))
    }
}

@Composable
private fun ExtensionTypeControl(selected: ExtensionType, onSelect: (ExtensionType) -> Unit) {
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
private fun ExtensionSearchAndActions(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
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
        Spacer(Modifier.size(8.dp))
        ExtensionStatusMenu(state.extensionStatus, state.extensionType) {
            onEvent(AppUiEvent.SelectExtensionStatus(it))
        }
        Spacer(Modifier.size(8.dp))
        CircleIconButton(
            label = "Manage extension sources",
            glyph = IconGlyph.SETTINGS,
            onClick = { onEvent(AppUiEvent.OpenExtensionSources) },
        )
    }
}

@Composable
private fun ExtensionStatusMenu(
    selected: ExtensionStatus,
    type: ExtensionType,
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
            ExtensionStatus.entries.filterNot {
                type == ExtensionType.SKILLS && it == ExtensionStatus.SETUP_PENDING
            }.forEach { status ->
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

@Composable
private fun ExtensionResults(state: AppUiState, onEvent: (AppUiEvent) -> Unit, modifier: Modifier) {
    val installed = state.extensionStatus == ExtensionStatus.INSTALLED
    val loading = when (state.extensionType) {
        ExtensionType.SKILLS -> if (installed) state.isSkillsLoading else state.isAvailableSkillsLoading
        ExtensionType.PLUGINS -> state.isPluginCatalogLoading
    }
    val error = when (state.extensionType) {
        ExtensionType.SKILLS -> if (installed) state.skillsError else state.availableSkillsError
        ExtensionType.PLUGINS -> state.pluginCatalogError
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = ChatDimensions.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.extensionNotice?.let { ExtensionNoticeCard(it.message, it.isError) }
        error?.let { ExtensionError(it, onEvent) }
        if (loading) ExtensionLoading(if (installed) "Loading extensions…" else "Refreshing catalog…")
        when (state.extensionType) {
            ExtensionType.SKILLS -> SkillResults(state, onEvent, Modifier.weight(1f))
            ExtensionType.PLUGINS -> PluginResults(state, onEvent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SkillResults(state: AppUiState, onEvent: (AppUiEvent) -> Unit, modifier: Modifier) {
    val query = state.extensionSearch.trim()
    val skills: List<Any> = when (state.extensionStatus) {
        ExtensionStatus.INSTALLED -> state.skills.filterNot { it.scope == AgentSkillScope.PLUGIN }
        ExtensionStatus.UNINSTALLED -> state.availableSkills
        ExtensionStatus.SETUP_PENDING, ExtensionStatus.UNAVAILABLE -> emptyList()
    }.filter {
        when (it) {
            is AgentSkill -> it.matches(query)
            is AgentSkillPackage -> it.matches(query)
            else -> false
        }
    }
    PagedExtensionList(
        itemCount = skills.size,
        pageKey = "skills:${state.extensionStatus}:$query",
        emptyMessage = emptyMessage(state, query),
        modifier = modifier,
    ) { index ->
        when (val skill = skills[index]) {
            is AgentSkill -> InstalledSkillCard(skill, state, onEvent)
            is AgentSkillPackage -> AvailableSkillCard(skill, state, onEvent)
            else -> Unit
        }
    }
}

@Composable
private fun PluginResults(state: AppUiState, onEvent: (AppUiEvent) -> Unit, modifier: Modifier) {
    val query = state.extensionSearch.trim()
    val installedIds = state.installedPlugins.map { it.reference.id }.toSet()
    val plugins = when (state.extensionStatus) {
        ExtensionStatus.INSTALLED -> state.installedPlugins.filterNot {
            it.reference.id in state.pendingPluginIds
        }
        ExtensionStatus.SETUP_PENDING -> state.installedPlugins.filter {
            it.reference.id in state.pendingPluginIds
        }
        ExtensionStatus.UNINSTALLED, ExtensionStatus.UNAVAILABLE -> state.availablePlugins.filter {
            it.uninstalledStatus(installedIds, state.unavailablePluginIds) == state.extensionStatus
        }
    }.filter { it.matches(query) }
    PagedExtensionList(
        itemCount = plugins.size,
        pageKey = "plugins:${state.extensionStatus}:$query",
        emptyMessage = pluginEmptyMessage(state, query),
        modifier = modifier,
    ) { index -> PluginCard(plugins[index], state, onEvent) }
}

@Composable
private fun InstalledSkillCard(skill: AgentSkill, state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    val operationId = "skill:${skill.path}"
    ExtensionCard(
        title = skill.readableTitle(),
        subtitle = skill.description,
        metadata = skill.scope.displayName,
        glyph = IconGlyph.INTELLIGENCE,
        actionLabel = if (skill.canUninstall) "Uninstall" else skill.scope.displayName,
        actionStyle = if (skill.canUninstall) ExtensionActionStyle.DANGER else ExtensionActionStyle.DISABLED,
        busy = state.extensionOperationId == operationId,
        error = state.actionError(operationId),
        controlsEnabled = skill.canUninstall && !state.isExtensionMutationLoading,
        onAction = { onEvent(AppUiEvent.RequestUninstallSkill(skill)) }.takeIf { skill.canUninstall },
    )
}

@Composable
private fun AvailableSkillCard(skill: AgentSkillPackage, state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    val operationId = "skill:${skill.id}"
    ExtensionCard(
        title = skill.displayName,
        subtitle = skill.description,
        metadata = skill.source.displayName,
        glyph = IconGlyph.INTELLIGENCE,
        actionLabel = "Install",
        actionStyle = ExtensionActionStyle.PRIMARY,
        busy = state.extensionOperationId == operationId,
        error = state.actionError(operationId),
        controlsEnabled = !state.isExtensionMutationLoading,
        onAction = { onEvent(AppUiEvent.InstallSkill(skill)) },
    )
}

@Composable
private fun PluginCard(plugin: AgentPluginSummary, state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    val operationId = "plugin:${plugin.reference.id}"
    val installed = state.extensionStatus == ExtensionStatus.INSTALLED
    val setupPending = state.extensionStatus == ExtensionStatus.SETUP_PENDING
    val unavailable = state.extensionStatus == ExtensionStatus.UNAVAILABLE
    ExtensionCard(
        title = plugin.displayName,
        subtitle = plugin.description.ifBlank { plugin.capabilities.joinToString() },
        metadata = plugin.reference.marketplaceName.replace('-', ' '),
        glyph = IconGlyph.PUZZLE,
        actionLabel = when {
            installed -> "Uninstall"
            setupPending -> "Connect"
            unavailable -> "Unavailable"
            else -> "Install"
        },
        actionStyle = when {
            installed -> ExtensionActionStyle.DANGER
            setupPending -> ExtensionActionStyle.PRIMARY
            unavailable -> ExtensionActionStyle.DISABLED
            else -> ExtensionActionStyle.PRIMARY
        },
        busy = state.extensionOperationId == operationId || state.extensionOperationId == "connect:${plugin.reference.id}",
        error = state.actionError(operationId) ?: state.actionError("connect:${plugin.reference.id}"),
        controlsEnabled = state.pluginActionsEnabled && !state.isExtensionMutationLoading && !unavailable,
        onAction = when {
            installed -> {
                { onEvent(AppUiEvent.RequestUninstallPlugin(plugin.reference, plugin.displayName)) }
            }
            setupPending -> {
                { onEvent(AppUiEvent.ConnectPlugin(plugin.reference)) }
            }
            unavailable -> null
            else -> {
                { onEvent(AppUiEvent.InstallPlugin(plugin.reference)) }
            }
        },
        secondaryActionLabel = "Uninstall ${plugin.displayName}".takeIf { setupPending },
        secondaryActionGlyph = IconGlyph.TRASH.takeIf { setupPending },
        onSecondaryAction = {
            onEvent(AppUiEvent.RequestUninstallPlugin(plugin.reference, plugin.displayName))
        }.takeIf { setupPending },
    )
}

private enum class ExtensionActionStyle { PRIMARY, DANGER, DISABLED }

@Composable
private fun ExtensionCard(
    title: String,
    subtitle: String,
    metadata: String,
    glyph: IconGlyph,
    actionLabel: String,
    actionStyle: ExtensionActionStyle,
    busy: Boolean,
    error: String?,
    controlsEnabled: Boolean,
    onAction: (() -> Unit)?,
    secondaryActionLabel: String? = null,
    secondaryActionGlyph: IconGlyph? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val largeText = LocalDensity.current.fontScale > ACCESSIBILITY_FONT_SCALE
    Surface(
        modifier = Modifier.fillMaxWidth().then(
            if (largeText) Modifier.heightIn(min = EXTENSION_CARD_HEIGHT) else Modifier.height(EXTENSION_CARD_HEIGHT),
        ),
        color = ChatColors.Elevated,
        shape = RoundedCornerShape(ChatDimensions.CardCorner),
        border = BorderStroke(1.dp, ChatColors.Border),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = ChatColors.ElevatedStrong,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(glyph, Modifier.size(23.dp), ChatColors.Primary)
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    metadata.replaceFirstChar(Char::uppercase),
                    color = ChatColors.Accent,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = ChatColors.Secondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                error?.let {
                    Text(it, color = ChatColors.Danger, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            Spacer(Modifier.size(8.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                if (secondaryActionLabel != null && secondaryActionGlyph != null && onSecondaryAction != null) {
                    IconButton(
                        onClick = onSecondaryAction,
                        enabled = controlsEnabled,
                        modifier = Modifier.size(40.dp).semantics { contentDescription = secondaryActionLabel },
                    ) {
                        AppIcon(secondaryActionGlyph, Modifier.size(20.dp), ChatColors.Danger)
                    }
                    Spacer(Modifier.size(4.dp))
                }
                Button(
                    onClick = { onAction?.invoke() },
                    enabled = controlsEnabled && onAction != null,
                    colors = when (actionStyle) {
                        ExtensionActionStyle.DANGER -> ButtonDefaults.buttonColors(
                            containerColor = ChatColors.Danger,
                            contentColor = ChatColors.Primary,
                        )
                        ExtensionActionStyle.PRIMARY -> ButtonDefaults.buttonColors()
                        ExtensionActionStyle.DISABLED -> ButtonDefaults.buttonColors(
                            disabledContainerColor = ChatColors.ElevatedStrong,
                            disabledContentColor = ChatColors.Secondary,
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.widthIn(min = 92.dp),
                ) { Text(actionLabel, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun PagedExtensionList(
    itemCount: Int,
    pageKey: String,
    emptyMessage: String,
    modifier: Modifier,
    item: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val largeText = LocalDensity.current.fontScale > ACCESSIBILITY_FONT_SCALE
        val fullPageSize = extensionPageSize(maxHeight.value)
        val showPager = itemCount > fullPageSize
        val availableForCards = (maxHeight - if (showPager) PAGE_FOOTER_HEIGHT else 0.dp)
            .coerceAtLeast(EXTENSION_CARD_HEIGHT)
        val pageSize = extensionPageSize(availableForCards.value)
        val pageCount = max(1, ceil(itemCount.toDouble() / pageSize).toInt())
        var page by rememberSaveable(pageKey, pageSize) { mutableIntStateOf(0) }
        LaunchedEffect(pageCount) {
            if (page >= pageCount) page = pageCount - 1
        }
        val first = page * pageSize
        val last = minOf(itemCount, first + pageSize)
        Column(Modifier.fillMaxSize()) {
            when {
                itemCount == 0 -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(emptyMessage, color = ChatColors.Secondary, textAlign = TextAlign.Center)
                }
                largeText -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(EXTENSION_CARD_SPACING),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items((first until last).toList()) { index -> item(index) }
                }
                else -> Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(EXTENSION_CARD_SPACING),
                ) {
                    for (index in first until last) item(index)
                }
            }
            if (showPager) PageFooter(page, pageCount, onPage = { page = it })
        }
    }
}

@Composable
private fun PageFooter(page: Int, pageCount: Int, onPage: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(PAGE_FOOTER_HEIGHT),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Text("‹") }
        if (pageCount > 1) {
            pageTokens(page, pageCount).forEachIndexed { index, token ->
                if (token == null) {
                    Text("…", color = ChatColors.Secondary, modifier = Modifier.padding(horizontal = 4.dp))
                } else {
                    TextButton(onClick = { onPage(token) }, enabled = token != page) {
                        Text(
                            "${token + 1}",
                            color = if (token == page) ChatColors.Accent else ChatColors.Primary,
                            fontWeight = if (token == page) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        } else {
            Text("Page 1", color = ChatColors.Secondary, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = { onPage(page + 1) }, enabled = page < pageCount - 1) { Text("›") }
    }
}

internal fun pageTokens(page: Int, pageCount: Int): List<Int?> {
    if (pageCount <= 5) return (0 until pageCount).toList()
    val visible = sortedSetOf(0, pageCount - 1, page - 1, page, page + 1).filter { it in 0 until pageCount }
    return buildList {
        visible.forEachIndexed { index, value ->
            if (index > 0 && value - visible[index - 1] > 1) add(null)
            add(value)
        }
    }
}

internal fun extensionPageSize(availableHeightDp: Float): Int =
    max(1, ((availableHeightDp + EXTENSION_CARD_SPACING.value) / EXTENSION_CARD_EXTENT.value).toInt())

private fun emptyMessage(state: AppUiState, query: String): String = when {
    query.isNotEmpty() -> "No extensions match “$query”"
    state.extensionStatus == ExtensionStatus.INSTALLED -> "No installed ${state.extensionType.label.lowercase()}"
    state.extensionStatus == ExtensionStatus.SETUP_PENDING -> "No plugins awaiting setup"
    state.extensionStatus == ExtensionStatus.UNINSTALLED -> "No ${state.extensionType.label.lowercase()} available to install"
    else -> "No unavailable ${state.extensionType.label.lowercase()}"
}

internal fun pluginEmptyMessage(state: AppUiState, query: String): String = when (state.pluginCatalogStatus) {
    PluginCatalogStatus.CONNECTING -> "Connecting to Codex…"
    PluginCatalogStatus.LOADING -> "Loading plugin catalog…"
    PluginCatalogStatus.NOT_LOADED -> "Waiting for the plugin catalog…"
    PluginCatalogStatus.STALE, PluginCatalogStatus.ERROR -> "Plugin catalog unavailable"
    PluginCatalogStatus.LIVE -> emptyMessage(state, query)
}

private fun AgentSkill.matches(query: String): Boolean = query.isEmpty() ||
    displayName.contains(query, true) || description.contains(query, true)

private fun AgentSkillPackage.matches(query: String): Boolean = query.isEmpty() ||
    displayName.contains(query, true) || description.contains(query, true)

private fun AgentPluginSummary.matches(query: String): Boolean = query.isEmpty() ||
    displayName.contains(query, true) || description.contains(query, true)

@Composable
internal fun ExtensionTopBar(title: String, onBack: () -> Unit) {
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
private fun ExtensionLoading(value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), color = ChatColors.Accent, strokeWidth = 2.dp)
        Spacer(Modifier.size(10.dp))
        Text(value, color = ChatColors.Secondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExtensionError(value: String, onEvent: (AppUiEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            value,
            color = ChatColors.Danger,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onEvent(AppUiEvent.RefreshExtensions) }) { Text("Retry") }
    }
}

@Composable
internal fun ExtensionNoticeCard(value: String, isError: Boolean) {
    Surface(
        color = (if (isError) ChatColors.Danger else ChatColors.PluginAccent).copy(alpha = 0.12f),
        shape = RoundedCornerShape(ChatDimensions.ControlCorner),
        border = BorderStroke(1.dp, if (isError) ChatColors.Danger else ChatColors.PluginAccent),
    ) {
        Text(
            value,
            color = if (isError) ChatColors.Danger else ChatColors.Primary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun AppUiState.actionError(operationId: String): String? =
    extensionActionError?.takeIf { it.operationId == operationId }?.message

private fun Modifier.statusAndNavigationPadding() = statusBarsPadding().navigationBarsPadding()

private val EXTENSION_CARD_HEIGHT = 96.dp
private val EXTENSION_CARD_SPACING = 10.dp
private val EXTENSION_CARD_EXTENT = EXTENSION_CARD_HEIGHT + EXTENSION_CARD_SPACING
private val PAGE_FOOTER_HEIGHT = 52.dp
private const val ACCESSIBILITY_FONT_SCALE = 1.3f
