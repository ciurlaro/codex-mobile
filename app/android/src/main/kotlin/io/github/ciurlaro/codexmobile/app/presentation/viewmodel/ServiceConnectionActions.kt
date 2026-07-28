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
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.core.PLAN_CLIENT_MESSAGE_PREFIX
import io.github.ciurlaro.codexmobile.core.AgentHook
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
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


internal fun AppViewModel.serviceConnectedAction(binder: CodexForegroundService.LocalBinder) {
    serviceStartPending = false
    serviceController = binder.controller
    notificationsEnabled = binder::notificationsEnabled
    signOutAction = binder::signOut
    serviceInstanceId = binder.serviceInstanceId
    reconciledPluginSourceIds = emptySet()
    if (signOutPending) {
        signOutPending = false
        binder.signOut()
    }
    serviceStateJob?.cancel()
    serviceStateJob = scope.launch {
        binder.controller.state.collect { session ->
            applySessionState(session, binder.notificationsEnabled())
            if (session.skillsRevision != skillsRevision) {
                skillsRevision = session.skillsRevision
                if (mutableState.value.skillsLoaded) loadSkills(forceReload = true)
            }
            if (session.pluginsRevision != pluginsRevision) {
                pluginsRevision = session.pluginsRevision
                if (pluginsJob?.isActive == true) {
                    pluginRefreshPending = true
                } else if (mutableState.value.pluginCatalogStatus != PluginCatalogStatus.NOT_LOADED) {
                    loadPluginCatalog(forceReload = true)
                }
                mutableState.update { it.copy(providerSettings = container.platform.providerSettings()) }
            }
            if (session.connectorsRevision != connectorsRevision) {
                connectorsRevision = session.connectorsRevision
                if (integrationsLoaded && connectorRefreshJob?.isActive != true) {
                    connectorRefreshJob = launch {
                        refreshConnectors(binder.controller, forceReload = false)
                    }
                }
            }
            if (session.isAuthenticated && !chatDataRequested) {
                chatDataRequested = true
                launch { refreshChatData(binder.controller) }
            }
            if (session.terminal) releaseServiceBinding()
        }
    }
}

internal suspend fun AppViewModel.refreshChatDataAction(controller: CodexSessionController) {
    val models = runCatching { controller.listModels() }.getOrDefault(emptyList())
    val conversations = runCatching { controller.listConversations() }.getOrDefault(emptyList())
    mutableState.update { current ->
        val selected = models.firstOrNull { it.id == current.selectedModel }
            ?: models.firstOrNull(AgentModel::isDefault)
            ?: models.firstOrNull()
        val effort = selected?.let { model ->
            current.selectedEffort?.takeIf(model.supportedEfforts::contains)
                ?: model.defaultEffort
        }
        val tier = selected?.let { model ->
            current.selectedSpeedTier?.takeIf { saved ->
                model.serviceTiers.any { it.id == saved }
            } ?: model.defaultServiceTier
        }
        current.copy(
            models = models,
            conversations = conversations,
            selectedModel = selected?.id ?: current.selectedModel,
            selectedEffort = effort ?: current.selectedEffort,
            selectedSpeedTier = tier,
        )
    }
    persistSelection()
    loadSkills(forceReload = false)
    loadPluginCatalog(forceReload = false)
    if (mutableState.value.pendingPluginSetups.isNotEmpty()) {
        integrationsLoaded = true
        refreshConnectors(controller, forceReload = true)
    }
    if (mutableState.value.screen == AppScreen.EXTENSIONS) loadCurrentExtensions(forceReload = false)
}

internal fun AppViewModel.releaseServiceBindingAction() {
    serviceConnection.unbind()
    serviceEnded()
}

internal fun AppViewModel.serviceEndedAction() {
    serviceStartPending = false
    val recoverAuthentication = authenticationHandoffPending()
    pendingConversationId = null
    serviceStateJob?.cancel()
    serviceStateJob = null
    cancelServiceRequests()
    serviceController = null
    notificationsEnabled = null
    signOutAction = null
    signOutPending = false
    serviceInstanceId = null
    reconciledPluginSourceIds = emptySet()
    pluginRefreshPending = false
    chatDataRequested = false
    mutableState.update {
        it.copy(
            statusMessage = when {
                recoverAuthentication -> "Completing sign-in…"
                it.isBackgroundActive -> "Background work ended"
                else -> it.statusMessage
            },
            sessionId = null,
            isAuthenticated = false,
            signInUrl = null,
            isAuthenticationInProgress = recoverAuthentication,
            isTurnActive = false,
            isBackgroundActive = false,
            skillsLoaded = false,
            availableSkillsLoaded = false,
            pluginCatalogStatus = if (it.plugins.isEmpty()) {
                PluginCatalogStatus.NOT_LOADED
            } else {
                PluginCatalogStatus.STALE
            },
            isSkillsLoading = false,
            isAvailableSkillsLoading = false,
            isExtensionSourceLoading = false,
            isExtensionMutationLoading = false,
            extensionOperationId = null,
            isConversationLoading = false,
            skillsError = null,
            availableSkillsError = null,
            pluginCatalogError = if (it.plugins.isEmpty()) null else "Codex disconnected; showing saved plugins.",
            extensionActionError = null,
        )
    }
    if (recoverAuthentication) {
        scope.launch {
            delay(AUTHENTICATION_RECOVERY_DELAY_MILLIS)
            if (serviceController == null && authenticationHandoffPending()) authenticate()
        }
    }
}

internal fun AppViewModel.cancelServiceRequestsAction() {
    skillsJob?.cancel()
    availableSkillsJob?.cancel()
    pluginsJob?.cancel()
    connectorAuthenticationJob?.cancel()
    connectorRefreshJob?.cancel()
    extensionSourceJob?.cancel()
    skillsJob = null
    availableSkillsJob = null
    pluginsJob = null
    connectorAuthenticationJob = null
    connectorRefreshJob = null
    extensionSourceJob = null
}
