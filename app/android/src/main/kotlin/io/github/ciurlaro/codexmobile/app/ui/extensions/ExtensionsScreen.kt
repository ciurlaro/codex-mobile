package io.github.ciurlaro.codexmobile.app.ui.extensions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.github.ciurlaro.codexmobile.app.presentation.event.AppUiEvent
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors

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
            text = {
                Text("This removes it from Codex Mobile. You can install it again while its source is available.")
            },
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
