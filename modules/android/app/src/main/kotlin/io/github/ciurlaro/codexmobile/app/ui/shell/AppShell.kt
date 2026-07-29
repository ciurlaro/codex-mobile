package io.github.ciurlaro.codexmobile.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.ui.chat.ChatScreen
import io.github.ciurlaro.codexmobile.app.ui.chat.ChatSelectorOverlay
import io.github.ciurlaro.codexmobile.app.ui.extensions.ExtensionsScreen
import io.github.ciurlaro.codexmobile.app.ui.settings.SettingsScreen
import io.github.ciurlaro.codexmobile.app.ui.settings.HooksScreen
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors

@Composable
internal fun AppShell(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = ChatColors.Background) {
        Box {
            when (state.screen) {
                AppScreen.CHAT -> ChatScreen(state, onEvent)
                AppScreen.SETTINGS -> SettingsScreen(state, onEvent)
                AppScreen.EXTENSIONS -> ExtensionsScreen(state, onEvent)
                AppScreen.HOOKS -> HooksScreen(state, onEvent)
            }
            if (state.activeSelector != null) {
                ChatSelectorOverlay(
                    state = state,
                    onEvent = onEvent,
                    aboveComposer = state.screen == AppScreen.CHAT,
                )
            }
        }
    }
    BackHandler(
        enabled = state.activeSelector != null || state.isHistoryOpen || state.screen != AppScreen.CHAT,
    ) {
        when {
            state.activeSelector == ChatSelector.SKILLS || state.activeSelector == ChatSelector.PLUGINS ->
                onEvent(AppUiEvent.OpenSelector(ChatSelector.TAGS))
            state.activeSelector != null -> onEvent(AppUiEvent.DismissSelector)
            state.isHistoryOpen -> onEvent(AppUiEvent.CloseHistory)
            state.screen == AppScreen.SETTINGS -> onEvent(AppUiEvent.CloseSettings)
            state.screen == AppScreen.HOOKS -> onEvent(AppUiEvent.CloseHooks)
            state.extensionSourcesOpen -> onEvent(AppUiEvent.CloseExtensionSources)
            state.screen == AppScreen.EXTENSIONS -> onEvent(AppUiEvent.CloseExtensions)
        }
    }
}
