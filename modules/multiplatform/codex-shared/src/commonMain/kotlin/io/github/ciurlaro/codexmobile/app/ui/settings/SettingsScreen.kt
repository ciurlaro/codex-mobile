package io.github.ciurlaro.codexmobile.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.formatting.effortLabel
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.state.selectedModelOrNull
import io.github.ciurlaro.codexmobile.app.ui.icons.CircleIconButton
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatDimensions

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
                    SettingsDivider()
                    SettingsRow(
                        glyph = IconGlyph.LINK,
                        title = "Hooks",
                        subtitle = if (state.hooks.isEmpty()) {
                            "Review and manage lifecycle hooks"
                        } else {
                            "${state.hooks.count { it.enabled }} of ${state.hooks.size} enabled"
                        },
                        onClick = { onEvent(AppUiEvent.OpenHooks) },
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
