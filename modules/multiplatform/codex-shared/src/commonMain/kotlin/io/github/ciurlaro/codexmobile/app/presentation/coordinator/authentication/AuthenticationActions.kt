package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun AppViewModel.authenticateAction() {
    setAuthenticationHandoffPending(true)
    if (
        serviceController != null &&
        (!mutableState.value.isBackgroundActive || serviceController?.state?.value?.terminal == true)
    ) {
        releaseServiceBinding()
    }
    mutableState.update {
        it.copy(
            statusMessage = "Starting protected background work…",
            signInUrl = null,
            isAuthenticationInProgress = true,
        )
    }
    serviceController?.let {
        it.authenticate()
        return
    }
    if (serviceStartPending) return
    serviceStartPending = true
    if (!sessionHost.startAndBind(authenticate = true)) {
        serviceStartPending = false
        setAuthenticationHandoffPending(false)
        mutableState.update {
            it.copy(
                statusMessage = "Android could not start background work; keep Codex Mobile visible and try again",
                isBackgroundActive = false,
                isAuthenticationInProgress = false,
            )
        }
    }
}

internal fun AppViewModel.cancelAuthenticationAction() {
    serviceStartPending = false
    setAuthenticationHandoffPending(false)
    mutableState.update { it.copy(statusMessage = "Cancelling sign-in…", isAuthenticationInProgress = false) }
    serviceController?.cancelAuthentication()
        ?: mutableState.update { it.copy(statusMessage = "Ready to sign in") }
}

internal fun AppViewModel.browserUnavailableAction() {
    setAuthenticationHandoffPending(false)
    mutableState.update {
        it.copy(statusMessage = "No browser can open the ChatGPT sign-in page", isAuthenticationInProgress = false)
    }
}

internal fun AppViewModel.performSignOut() {
    setAuthenticationHandoffPending(false)
    preferenceState = preferenceState.copy(hadAuthenticatedSession = false)
    scope.launch { uiPreferences.setHadAuthenticatedSession(false) }
    mutableState.update {
        it.copy(
            statusMessage = "Signing out…",
            signInUrl = null,
            installedPlugins = emptyList(),
            availablePlugins = emptyList(),
            pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
            pluginCatalogError = null,
        )
    }
    signOutAction?.invoke() ?: run {
        signOutPending = true
        if (!sessionHost.bind(create = true)) {
            signOutPending = false
            mutableState.update { it.copy(statusMessage = "ChatGPT sign-out could not start; try again") }
        }
    }
}

internal fun AppViewModel.eraseAppDataAction() {
    setAuthenticationHandoffPending(false)
    mutableState.update { it.copy(statusMessage = "Erasing Codex Mobile data…") }
    if (!platform.eraseAppData()) {
        mutableState.update { it.copy(statusMessage = "Android could not erase app data; try again") }
    }
}

internal fun AppViewModel.authenticationHandoffPendingAction(): Boolean =
    preferenceState.authenticationHandoffPending

internal fun AppViewModel.setAuthenticationHandoffPendingAction(pending: Boolean) {
    if (preferenceState.authenticationHandoffPending == pending) return
    preferenceState = preferenceState.copy(authenticationHandoffPending = pending)
    scope.launch { uiPreferences.setAuthenticationHandoffPending(pending) }
}
