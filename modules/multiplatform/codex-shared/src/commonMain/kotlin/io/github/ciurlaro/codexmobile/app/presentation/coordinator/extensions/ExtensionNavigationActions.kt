package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun AppViewModel.openExtensionsAction(type: ExtensionType, returnScreen: AppScreen) {
    cancelExtensionNotice()
    mutableState.update {
        it.copy(
            screen = AppScreen.EXTENSIONS,
            extensionsReturnScreen = returnScreen,
            extensionType = type,
            extensionStatus = ExtensionStatus.INSTALLED,
            extensionSearch = "",
            extensionNotice = null,
            isHistoryOpen = false,
            activeSelector = null,
        )
    }
    if (
        serviceController == null &&
        preferenceState.hadAuthenticatedSession &&
        !mutableState.value.isAuthenticationInProgress
    ) {
        authenticate()
    }
    loadCurrentExtensions(forceReload = false)
}

internal fun AppViewModel.closeExtensionsAction() {
    cancelExtensionNotice()
    mutableState.update {
        it.copy(
            screen = it.extensionsReturnScreen,
            pendingExtensionRemoval = null,
            extensionActionError = null,
            extensionNotice = null,
        )
    }
}

internal fun AppViewModel.refreshExtensionsAction() {
    cancelExtensionNotice()
    mutableState.update {
        it.copy(
            extensionActionError = null,
            unavailablePluginIds = emptySet(),
            extensionNotice = null,
        )
    }
    loadCurrentExtensions(forceReload = true)
    if (mutableState.value.pendingPluginSetups.isNotEmpty()) {
        serviceController?.let { controller ->
            scope.launch { refreshConnectors(controller, forceReload = true) }
        }
    }
}

internal fun AppViewModel.selectExtensionTypeAction(type: ExtensionType) {
    mutableState.update {
        it.copy(
            extensionType = type,
            extensionStatus = if (type == ExtensionType.SKILLS) {
                ExtensionStatus.INSTALLED
            } else {
                it.extensionStatus
            },
            extensionSearch = "",
            extensionActionError = null,
        )
    }
    loadCurrentExtensions(forceReload = false)
}

internal fun AppViewModel.selectExtensionStatusAction(status: ExtensionStatus) {
    mutableState.update {
        it.copy(extensionStatus = status, extensionSearch = "", extensionActionError = null)
    }
    loadCurrentExtensions(forceReload = false)
}

internal fun AppViewModel.searchExtensionsAction(query: String) {
    mutableState.update { it.copy(extensionSearch = query) }
}
