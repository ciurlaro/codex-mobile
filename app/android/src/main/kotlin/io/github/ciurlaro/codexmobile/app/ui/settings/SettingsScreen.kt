package io.github.ciurlaro.codexmobile.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.formatting.effortLabel
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.state.selectedModelOrNull
import io.github.ciurlaro.codexmobile.app.ui.icons.AppIcon
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions
import io.github.ciurlaro.codexmobile.platform.android.ProviderSettingsEntry

@Composable
internal fun SettingsScreen(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatColors.Background)
            .statusBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(ChatDimensions.TopBarHeight)
                .padding(horizontal = ChatDimensions.ScreenPadding),
        ) {
            CircleIconButton(
                label = "Back to chat",
                glyph = IconGlyph.BACK,
                modifier = Modifier.align(Alignment.CenterStart),
            ) { onEvent(AppUiEvent.CloseSettings) }
            Text(
                "Settings",
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { heading() },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ChatDimensions.ScreenPadding,
                end = ChatDimensions.ScreenPadding,
                top = 8.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item("model-settings") {
                SettingsGroup("Codex") {
                    SettingsRow(
                        glyph = IconGlyph.BRAIN,
                        title = "Default model",
                        subtitle = state.selectedModelOrNull()?.displayName ?: state.selectedModel ?: "Unavailable",
                        onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.MODEL)) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.INTELLIGENCE,
                        title = "Default Intelligence",
                        subtitle = state.selectedEffort?.let(::effortLabel) ?: "Unavailable",
                        onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.EFFORT)) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.SPEED,
                        title = "Default speed",
                        subtitle = state.selectedModelOrNull()?.serviceTiers
                            ?.firstOrNull { it.id == state.selectedSpeedTier }?.name ?: "Default",
                        onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.SPEED)) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.SHIELD,
                        title = "Approval policy",
                        subtitle = state.approvalPreset.displayName,
                        onClick = { onEvent(AppUiEvent.OpenSelector(ChatSelector.APPROVAL)) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.PUZZLE,
                        title = "Skills & plugins",
                        subtitle = "${state.skills.count { it.enabled }} skills · " +
                            "${state.plugins.count { it.installed && it.enabled }} plugins",
                        onClick = { onEvent(AppUiEvent.OpenExtensions()) },
                    )
                }
            }
            item("access-settings") {
                SettingsGroup("Android access") {
                    SettingsRow(
                        glyph = IconGlyph.FOLDER,
                        title = if (state.workspacePath != null) "Change workspace" else "Select workspace",
                        subtitle = state.workspacePath ?: "No folder selected",
                        onClick = { onEvent(AppUiEvent.SelectScope) },
                    )
                }
            }
            item("plugin-settings") {
                SettingsGroup("Plugins") {
                    SettingsRow(
                        glyph = IconGlyph.SETTINGS,
                        title = "Plugin settings",
                        subtitle = if (state.providerSettings.isEmpty()) {
                            "Available when an installed plugin requires specific settings"
                        } else {
                            "Configure ${state.providerSettings.size} installed " +
                                if (state.providerSettings.size == 1) "plugin" else "plugins"
                        },
                        enabled = state.providerSettings.isNotEmpty(),
                        onClick = { onEvent(AppUiEvent.ShowPluginSettings) }
                            .takeIf { state.providerSettings.isNotEmpty() },
                    )
                }
            }
            item("privacy-settings") {
                SettingsGroup("Privacy and data") {
                    SettingsRow(
                        glyph = IconGlyph.LOCK,
                        title = "Privacy details",
                        subtitle = "How Codex Mobile handles local and OpenAI data",
                        onClick = { onEvent(AppUiEvent.ShowPrivacy) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.TRASH,
                        title = "Erase Codex Mobile data",
                        subtitle = "Credentials, history, settings and access",
                        danger = true,
                        onClick = { onEvent(AppUiEvent.ShowEraseConfirmation) },
                    )
                }
            }
            item("account-settings") {
                SettingsGroup("Account") {
                    when {
                        state.isAuthenticated -> SettingsRow(
                            glyph = IconGlyph.LOGOUT,
                            title = "Sign out of ChatGPT",
                            danger = true,
                            onClick = { onEvent(AppUiEvent.SignOut) },
                        )

                        state.signInUrl != null -> {
                            SettingsRow(
                                glyph = IconGlyph.USER,
                                title = "Open sign-in again",
                                subtitle = "Complete account sign-in in your browser",
                                onClick = { onEvent(AppUiEvent.OpenSignIn) },
                            )
                            SettingsDivider()
                            SettingsRow(
                                glyph = IconGlyph.BACK,
                                title = "Cancel sign-in",
                                onClick = { onEvent(AppUiEvent.CancelAuthentication) },
                            )
                        }

                        else -> SettingsRow(
                            glyph = IconGlyph.USER,
                            title = "Sign in with ChatGPT",
                            onClick = { onEvent(AppUiEvent.Authenticate) },
                        )
                    }
                }
            }
            item("about-settings") {
                SettingsGroup("About") {
                    SettingsRow(
                        glyph = IconGlyph.INFO,
                        title = "Codex Mobile",
                        subtitle = "Native Android Codex client",
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = ChatColors.Secondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(ChatDimensions.CardCorner),
            color = ChatColors.ElevatedStrong,
            border = BorderStroke(1.dp, ChatColors.Border),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    glyph: IconGlyph,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val action = if (onClick == null || !enabled) Modifier else Modifier.clickable(onClick = onClick)
    val contentColor = when {
        !enabled -> ChatColors.Secondary.copy(alpha = 0.5f)
        danger -> ChatColors.Danger
        else -> ChatColors.Primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(action)
            .semantics(mergeDescendants = true) { if (onClick != null) role = Role.Button }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            glyph,
            Modifier.size(25.dp),
            contentColor,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = contentColor,
                style = MaterialTheme.typography.bodyLarge,
            )
            subtitle?.let {
                Text(
                    it,
                    color = if (enabled) ChatColors.Secondary else ChatColors.Secondary.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null && enabled) {
            Spacer(Modifier.width(8.dp))
            AppIcon(IconGlyph.CHEVRON_RIGHT, Modifier.size(18.dp), ChatColors.Secondary)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = ChatColors.Background,
        thickness = 2.dp,
        modifier = Modifier.padding(start = 60.dp),
    )
}

@Composable
internal fun PluginSettingsDialog(
    providers: List<ProviderSettingsEntry>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plugin settings") },
        text = {
            Column {
                providers.forEachIndexed { index, provider ->
                    SettingsRow(
                        glyph = IconGlyph.PUZZLE,
                        title = provider.displayName,
                        subtitle = provider.message ?: if (provider.removalNeedsRetry) {
                            "Removal needs retry"
                        } else {
                            "Configure plugin"
                        },
                        onClick = { onSelect(provider.pluginId) },
                    )
                    if (index < providers.lastIndex) SettingsDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
