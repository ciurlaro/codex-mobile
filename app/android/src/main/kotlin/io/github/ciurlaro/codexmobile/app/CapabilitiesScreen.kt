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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillScope

@Composable
internal fun CapabilitiesScreen(state: MainUiState, onEvent: (ChatUiEvent) -> Unit) {
    state.selectedPlugin?.let {
        PluginDetailScreen(it, state, onEvent)
        return
    }
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        CapabilityTopBar("Skills & plugins") { onEvent(ChatUiEvent.CloseCapabilities) }
        Text(
            "Beta · powered by the bundled Codex app-server",
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = ChatDimensions.ScreenPadding),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = ChatDimensions.ScreenPadding, vertical = 10.dp)) {
            CapabilityTab.entries.forEach { tab ->
                val selected = state.capabilityTab == tab
                Surface(
                    modifier = Modifier.weight(1f).clickable {
                        onEvent(ChatUiEvent.SelectCapabilityTab(tab))
                    },
                    color = if (selected) ChatColors.ElevatedStrong else Color.Transparent,
                    shape = RoundedCornerShape(ChatDimensions.ControlCorner),
                ) {
                    Text(
                        tab.name.lowercase().replaceFirstChar(Char::uppercase),
                        modifier = Modifier.padding(12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.capabilitySearch,
            onValueChange = { onEvent(ChatUiEvent.SearchCapabilities(it)) },
            label = { Text("Search ${state.capabilityTab.name.lowercase()}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ChatDimensions.ScreenPadding),
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
        state.capabilityError?.let {
            Text(
                it,
                color = ChatColors.Danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = ChatDimensions.ScreenPadding, vertical = 6.dp),
            )
        }
        Box(Modifier.fillMaxSize()) {
            when (state.capabilityTab) {
                CapabilityTab.SKILLS -> SkillList(state, onEvent)
                CapabilityTab.PLUGINS -> PluginList(state, onEvent)
            }
            if (state.isCapabilitiesLoading) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(30.dp),
                    color = ChatColors.Accent,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun SkillList(state: MainUiState, onEvent: (ChatUiEvent) -> Unit) {
    val query = state.capabilitySearch.trim()
    val skills = state.skills.filter {
        query.isEmpty() || it.displayName.contains(query, true) || it.description.contains(query, true)
    }.groupBy(AgentSkill::scope)
    LazyColumn(
        contentPadding = PaddingValues(ChatDimensions.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AgentSkillScope.entries.forEach { scope ->
            val group = skills[scope].orEmpty()
            if (group.isNotEmpty()) {
                item("skill-${scope.name}") { SectionTitle(scope.displayName) }
                items(group, key = AgentSkill::path) { skill ->
                    CapabilityCard(
                        title = skill.displayName,
                        subtitle = skill.description,
                        enabled = skill.enabled,
                        onToggle = { onEvent(ChatUiEvent.ToggleSkill(skill.path, it)) },
                    )
                }
            }
        }
        if (skills.isEmpty() && !state.isCapabilitiesLoading) {
            item("no-skills") { EmptyCapabilities("No matching skills") }
        }
    }
}

@Composable
private fun PluginList(state: MainUiState, onEvent: (ChatUiEvent) -> Unit) {
    val query = state.capabilitySearch.trim()
    val plugins = state.plugins.filter {
        query.isEmpty() || it.displayName.contains(query, true) || it.description.contains(query, true)
    }
    LazyColumn(
        contentPadding = PaddingValues(ChatDimensions.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf(true to "Installed", false to "Available").forEach { (installed, label) ->
            val group = plugins.filter { it.installed == installed }
            if (group.isNotEmpty()) {
                item("plugin-$label") { SectionTitle(label) }
                items(group, key = { it.reference.id }) { plugin -> PluginCard(plugin, onEvent) }
            }
        }
        if (plugins.isEmpty() && !state.isCapabilitiesLoading) {
            item("no-plugins") { EmptyCapabilities("No matching official plugins") }
        }
    }
}

@Composable
private fun PluginCard(plugin: AgentPluginSummary, onEvent: (ChatUiEvent) -> Unit) {
    CapabilityCard(
        title = plugin.displayName,
        subtitle = plugin.description.ifBlank { plugin.capabilities.joinToString() },
        enabled = plugin.enabled,
        onClick = { onEvent(ChatUiEvent.OpenPlugin(plugin.reference)) },
        onToggle = if (plugin.installed) {
            { enabled -> onEvent(ChatUiEvent.TogglePlugin(plugin.reference.id, enabled)) }
        } else null,
    )
}

@Composable
private fun CapabilityCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
    onToggle: ((Boolean) -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        color = ChatColors.Elevated,
        shape = RoundedCornerShape(ChatDimensions.CardCorner),
        border = BorderStroke(1.dp, ChatColors.Border),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = ChatColors.Secondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            onToggle?.let { Switch(checked = enabled, onCheckedChange = it) }
        }
    }
}

@Composable
private fun PluginDetailScreen(
    detail: AgentPluginDetail,
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusAndNavigationPadding()) {
        CapabilityTopBar(detail.summary.displayName) { onEvent(ChatUiEvent.ClosePluginDetails) }
        LazyColumn(
            contentPadding = PaddingValues(ChatDimensions.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("description") {
                Text(detail.description.ifBlank { detail.summary.description })
                Spacer(Modifier.height(8.dp))
                Text("Official OpenAI catalog · Beta", color = ChatColors.Secondary)
            }
            if (detail.summary.capabilities.isNotEmpty()) {
                item("capabilities") {
                    SectionTitle("Capabilities")
                    Text(detail.summary.capabilities.joinToString(" · "))
                }
            }
            if (detail.skills.isNotEmpty()) {
                item("skills") {
                    SectionTitle("Skills")
                    Text(detail.skills.joinToString("\n") { "• ${it.name}" })
                }
            }
            if (detail.connectors.isNotEmpty() || detail.mcpServers.isNotEmpty()) {
                item("connectors") {
                    SectionTitle("Connectors")
                    Text((detail.connectors.map { it.name } + detail.mcpServers).joinToString("\n") { "• $it" })
                }
            }
            if (detail.hookCount > 0) {
                item("hooks") {
                    Text("This plugin declares hooks. Codex Mobile does not load or run hooks.", color = ChatColors.Danger)
                }
            }
            listOfNotNull(
                detail.summary.websiteUrl?.let { "Website" to it },
                detail.summary.privacyPolicyUrl?.let { "Privacy policy" to it },
                detail.summary.termsOfServiceUrl?.let { "Terms of service" to it },
            ).takeIf { it.isNotEmpty() }?.let { links ->
                item("links") {
                    SectionTitle("Provider links")
                    links.forEach { (label, url) ->
                        Text("$label: $url", color = ChatColors.Secondary)
                    }
                }
            }
            item("action") {
                when {
                    !detail.summary.installed -> Button(
                        enabled = detail.summary.available && !state.isCapabilitiesLoading,
                        onClick = { onEvent(ChatUiEvent.InstallPlugin(detail.summary.reference)) },
                    ) { Text("Install") }
                    else -> Button(
                        enabled = !state.isCapabilitiesLoading,
                        onClick = { onEvent(ChatUiEvent.UninstallPlugin(detail.summary.reference.id)) },
                    ) { Text("Uninstall") }
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

private fun Modifier.statusAndNavigationPadding() =
    statusBarsPadding().navigationBarsPadding()
