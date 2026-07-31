package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.persistence.AppPreferenceState
import kotlinx.coroutines.flow.update

internal fun AppViewModel.applyLoadedPreferencesAction(preferences: AppPreferenceState) {
    preferenceState = preferences
    if (preferences.pendingPluginSetups.isNotEmpty()) integrationsLoaded = true
    mutableState.update {
        it.copy(
            selectedModel = preferences.selectedModel,
            selectedEffort = preferences.selectedEffort,
            selectedSpeedTier = preferences.selectedSpeedTier,
            pinnedConversationIds = preferences.pinnedConversationIds,
            recentInvocationKeys = preferences.recentInvocationKeys,
            approvalPreset = preferences.approvalPreset,
            pendingPluginSetups = preferences.pendingPluginSetups,
        )
    }
    if (preferences.authenticationHandoffPending) {
        mutableState.update {
            it.copy(statusMessage = "Completing sign-in…", isAuthenticationInProgress = true)
        }
        authenticate()
    }
}
