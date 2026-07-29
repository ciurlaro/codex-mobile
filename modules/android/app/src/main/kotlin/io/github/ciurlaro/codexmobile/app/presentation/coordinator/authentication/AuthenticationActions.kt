package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.app.lifecycle.CodexMobileApplication
import io.github.ciurlaro.codexmobile.app.persistence.AppPreferencesStore
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.planCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.withoutActiveInvocationToken
import io.github.ciurlaro.codexmobile.app.presentation.invocation.withRecentInvocation
import io.github.ciurlaro.codexmobile.app.presentation.mapper.toChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionActionError
import io.github.ciurlaro.codexmobile.app.presentation.model.CustomExtensionSource
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.afterExpiry
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionRemoval
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_URL
import io.github.ciurlaro.codexmobile.app.presentation.model.OPENAI_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.canonicalPluginSourceId
import io.github.ciurlaro.codexmobile.app.presentation.model.enabledMarketplaceNames
import io.github.ciurlaro.codexmobile.app.presentation.model.initialExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.reconcilePendingPluginSetups
import io.github.ciurlaro.codexmobile.app.presentation.state.withNewChat
import io.github.ciurlaro.codexmobile.app.presentation.state.withStreamingAssistant
import io.github.ciurlaro.codexmobile.app.presentation.state.withSubmittedTurn
import io.github.ciurlaro.codexmobile.app.presentation.state.withoutConversation
import io.github.ciurlaro.codexmobile.app.presentation.state.connectorsNeedingOnUseAuthentication
import io.github.ciurlaro.codexmobile.app.presentation.state.selectedModelOrNull
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionController
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionState
import io.github.ciurlaro.codexmobile.app.session.background.CodexForegroundService
import io.github.ciurlaro.codexmobile.app.session.background.CodexServiceConnection
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.PLAN_CLIENT_MESSAGE_PREFIX
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginSummary
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.SessionId
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull


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
    val authorization = container.backgroundSessions.authorizeStart()
    try {
        appContext.startForegroundService(
            CodexForegroundService.startIntent(appContext, authorization, authenticate = true),
        )
        check(serviceConnection.bind(Context.BIND_AUTO_CREATE)) { "Codex service binding failed" }
    } catch (_: Exception) {
        serviceStartPending = false
        container.backgroundSessions.revokeStart(authorization)
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
    uiPreferences.setHadAuthenticatedSession(false)
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
        if (!serviceConnection.bind(Context.BIND_AUTO_CREATE)) {
            signOutPending = false
            mutableState.update { it.copy(statusMessage = "ChatGPT sign-out could not start; try again") }
        }
    }
}

internal fun AppViewModel.eraseAppDataAction() {
    setAuthenticationHandoffPending(false)
    mutableState.update { it.copy(statusMessage = "Erasing Codex Mobile data…") }
    val accepted = appContext.getSystemService(ActivityManager::class.java)
        .clearApplicationUserData()
    if (!accepted) {
        mutableState.update { it.copy(statusMessage = "Android could not erase app data; try again") }
    }
}

internal fun AppViewModel.authenticationHandoffPendingAction(): Boolean = uiPreferences.authenticationHandoffPending()

internal fun AppViewModel.setAuthenticationHandoffPendingAction(pending: Boolean) {
    uiPreferences.setAuthenticationHandoffPending(pending)
}
