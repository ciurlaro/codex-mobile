package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionController
import io.github.ciurlaro.codexmobile.agent.AgentModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun AppViewModel.serviceConnectedAction(handle: AppSessionHandle) {
    serviceStartPending = false
    serviceController = handle.controller
    notificationsEnabled = handle.notificationsEnabled
    signOutAction = handle.signOut
    serviceInstanceId = handle.serviceInstanceId
    if (signOutPending) {
        signOutPending = false
        handle.signOut()
    }
    serviceStateJob?.cancel()
    serviceStateJob = scope.launch {
        handle.controller.state.collect { session ->
            applySessionState(session, handle.notificationsEnabled())
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
            }
            if (session.connectorsRevision != connectorsRevision) {
                connectorsRevision = session.connectorsRevision
                if (integrationsLoaded && connectorRefreshJob?.isActive != true) {
                    connectorRefreshJob = launch {
                        refreshConnectors(handle.controller, forceReload = false)
                    }
                }
            }
            if (session.isAuthenticated && !chatDataRequested) {
                chatDataRequested = true
                launch { refreshChatData(handle.controller) }
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
    sessionHost.unbind()
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
            pluginCatalogStatus = if (it.plugins.isEmpty()) {
                PluginCatalogStatus.NOT_LOADED
            } else {
                PluginCatalogStatus.STALE
            },
            isSkillsLoading = false,
            isExtensionMutationLoading = false,
            extensionOperationId = null,
            isConversationLoading = false,
            skillsError = null,
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
    pluginsJob?.cancel()
    connectorAuthenticationJob?.cancel()
    connectorRefreshJob?.cancel()
    skillsJob = null
    pluginsJob = null
    connectorAuthenticationJob = null
    connectorRefreshJob = null
}
