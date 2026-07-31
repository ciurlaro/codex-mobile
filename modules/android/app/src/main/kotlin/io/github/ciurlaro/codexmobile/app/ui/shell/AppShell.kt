package io.github.ciurlaro.codexmobile.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector

@Composable
internal fun AppShell(
    state: AppUiState,
    onEvent: (AppUiEvent) -> Unit,
) {
    AppContent(state, onEvent)
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
            state.screen == AppScreen.EXTENSIONS -> onEvent(AppUiEvent.CloseExtensions)
        }
    }
}
