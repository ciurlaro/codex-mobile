package io.github.ciurlaro.codexmobile.app.ui.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.invocation.readableTitle
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.uninstalledStatus
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentSkillScope

@Composable
internal fun ExtensionResults(state: AppUiState, onEvent: (AppUiEvent) -> Unit, modifier: Modifier) {
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
    }.filter { skill ->
        when (skill) {
            is AgentSkill -> skill.matches(query)
            is AgentSkillPackage -> skill.matches(query)
            else -> false
        }
    }
    PagedExtensionList(
        itemCount = skills.size,
        pageKey = "skills:${state.extensionStatus}:$query",
        emptyMessage = extensionEmptyMessage(state, query),
        modifier = modifier,
    ) { index ->
        when (val skill = skills[index]) {
            is AgentSkill -> InstalledSkillCard(skill, state, onEvent)
            is AgentSkillPackage -> AvailableSkillCard(skill, state, onEvent)
        }
    }
}

@Composable
private fun PluginResults(state: AppUiState, onEvent: (AppUiEvent) -> Unit, modifier: Modifier) {
    val query = state.extensionSearch.trim()
    val installedIds = state.installedPlugins.mapTo(mutableSetOf()) { it.reference.id }
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
        busy = state.extensionOperationId == operationId ||
            state.extensionOperationId == "connect:${plugin.reference.id}",
        error = state.actionError(operationId) ?: state.actionError("connect:${plugin.reference.id}"),
        controlsEnabled = state.pluginActionsEnabled && !state.isExtensionMutationLoading && !unavailable,
        onAction = when {
            installed -> ({ onEvent(AppUiEvent.RequestUninstallPlugin(plugin.reference, plugin.displayName)) })
            setupPending -> ({ onEvent(AppUiEvent.ConnectPlugin(plugin.reference)) })
            unavailable -> null
            else -> ({ onEvent(AppUiEvent.InstallPlugin(plugin.reference)) })
        },
        secondaryActionLabel = "Uninstall ${plugin.displayName}".takeIf { setupPending },
        secondaryActionGlyph = IconGlyph.TRASH.takeIf { setupPending },
        onSecondaryAction = {
            onEvent(AppUiEvent.RequestUninstallPlugin(plugin.reference, plugin.displayName))
        }.takeIf { setupPending },
    )
}

private fun extensionEmptyMessage(state: AppUiState, query: String): String = when {
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
    PluginCatalogStatus.LIVE -> extensionEmptyMessage(state, query)
}

private fun AgentSkill.matches(query: String) = query.isEmpty() ||
    displayName.contains(query, true) || description.contains(query, true)

private fun AgentSkillPackage.matches(query: String) = query.isEmpty() ||
    displayName.contains(query, true) || description.contains(query, true)

private fun AgentPluginSummary.matches(query: String) = query.isEmpty() ||
    displayName.contains(query, true) || description.contains(query, true)
