package io.github.ciurlaro.codexmobile.app

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    onEvent: (ChatUiEvent) -> Unit,
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
            ) { onEvent(ChatUiEvent.CloseSettings) }
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
                        glyph = IconGlyph.SETTINGS,
                        title = "Default model",
                        subtitle = state.selectedModelOrNull()?.displayName ?: state.selectedModel ?: "Unavailable",
                        onClick = { onEvent(ChatUiEvent.OpenSelector(ChatSelector.MODEL)) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.INTELLIGENCE,
                        title = "Default Intelligence",
                        subtitle = state.selectedEffort?.let(::effortLabel) ?: "Unavailable",
                        onClick = { onEvent(ChatUiEvent.OpenSelector(ChatSelector.EFFORT)) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.SPEED,
                        title = "Default speed",
                        subtitle = state.selectedModelOrNull()?.serviceTiers
                            ?.firstOrNull { it.id == state.selectedSpeedTier }?.name ?: "Default",
                        onClick = { onEvent(ChatUiEvent.OpenSelector(ChatSelector.SPEED)) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.SHIELD,
                        title = "Approval policy",
                        subtitle = state.approvalPreset.displayName,
                        onClick = { onEvent(ChatUiEvent.OpenSelector(ChatSelector.APPROVAL)) },
                    )
                }
            }
            item("access-settings") {
                SettingsGroup("Android access") {
                    SettingsRow(
                        glyph = IconGlyph.FOLDER,
                        title = if (state.workspacePath != null) "Change workspace" else "Select workspace",
                        subtitle = state.workspacePath ?: "No folder selected",
                        onClick = { onEvent(ChatUiEvent.SelectScope) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.STORAGE,
                        title = "Manage storage permission",
                        subtitle = if (state.hasStorageAccess) {
                            "All-files access enabled; workspace is the shell starting folder"
                        } else {
                            "All-files access is required for shell file operations"
                        },
                        onClick = { onEvent(ChatUiEvent.ManageStorage) },
                    )
                    if (state.workspacePath != null) {
                        SettingsDivider()
                        SettingsRow(
                            glyph = IconGlyph.CLOSE,
                            title = "Clear workspace selection",
                            danger = true,
                            onClick = { onEvent(ChatUiEvent.ClearWorkspace) },
                        )
                    }
                }
            }
            item("integration-settings") {
                SettingsGroup("Integrations") {
                    SettingsRow(
                        glyph = IconGlyph.LINK,
                        title = "Integrations",
                        subtitle = when {
                            state.isTelegramConnected -> "Telegram connected"
                            state.isTelegramAvailable -> "Telegram available"
                            else -> "No integrations available"
                        },
                        onClick = { onEvent(ChatUiEvent.ShowIntegrations) },
                    )
                }
            }
            item("privacy-settings") {
                SettingsGroup("Privacy and data") {
                    SettingsRow(
                        glyph = IconGlyph.LOCK,
                        title = "Privacy details",
                        subtitle = "How Codex Mobile handles local and OpenAI data",
                        onClick = { onEvent(ChatUiEvent.ShowPrivacy) },
                    )
                    if (state.isBackgroundActive) {
                        SettingsDivider()
                        SettingsRow(
                            glyph = IconGlyph.STOP,
                            title = "Stop background work",
                            subtitle = "Stop the active Codex runtime",
                            onClick = { onEvent(ChatUiEvent.StopBackground) },
                        )
                    }
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.TRASH,
                        title = "Erase Codex Mobile data",
                        subtitle = "Credentials, history, settings and access",
                        danger = true,
                        onClick = { onEvent(ChatUiEvent.ShowEraseConfirmation) },
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
                            onClick = { onEvent(ChatUiEvent.SignOut) },
                        )

                        state.signInUrl != null -> {
                            SettingsRow(
                                glyph = IconGlyph.USER,
                                title = "Open sign-in again",
                                subtitle = "Complete account sign-in in your browser",
                                onClick = { onEvent(ChatUiEvent.OpenSignIn) },
                            )
                            SettingsDivider()
                            SettingsRow(
                                glyph = IconGlyph.BACK,
                                title = "Cancel sign-in",
                                onClick = { onEvent(ChatUiEvent.CancelAuthentication) },
                            )
                        }

                        else -> SettingsRow(
                            glyph = IconGlyph.USER,
                            title = "Sign in with ChatGPT",
                            onClick = { onEvent(ChatUiEvent.Authenticate) },
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
    onClick: (() -> Unit)? = null,
) {
    val action = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
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
            if (danger) ChatColors.Danger else ChatColors.Primary,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (danger) ChatColors.Danger else ChatColors.Primary,
                style = MaterialTheme.typography.bodyLarge,
            )
            subtitle?.let {
                Text(
                    it,
                    color = ChatColors.Secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
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
