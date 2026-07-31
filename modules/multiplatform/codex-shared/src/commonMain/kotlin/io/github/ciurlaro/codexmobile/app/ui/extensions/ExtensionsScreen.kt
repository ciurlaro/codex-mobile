package io.github.ciurlaro.codexmobile.app.ui.extensions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors

@Composable
internal fun ExtensionsScreen(state: AppUiState, onEvent: (AppUiEvent) -> Unit) {
    ExtensionCatalog(state, onEvent)
    state.pendingExtensionRemoval?.let { removal ->
        AlertDialog(
            onDismissRequest = { onEvent(AppUiEvent.DismissExtensionRemoval) },
            title = { Text("Uninstall ${removal.displayName}?") },
            text = { Text("This removes the plugin from Codex. You can install it again from the official catalog.") },
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
