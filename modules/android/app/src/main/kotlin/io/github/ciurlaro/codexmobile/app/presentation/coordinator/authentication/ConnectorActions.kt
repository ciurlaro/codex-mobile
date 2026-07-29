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


internal fun AppViewModel.connectorAuthenticationReturnedAction() {
    val connectorId = mutableState.value.connectorAuthName ?: return
    mutableState.update {
        it.copy(
            connectorAuthUrl = null,
            connectorAuthName = null,
        )
    }
    val controller = serviceController ?: run {
        pendingConnectorAuthentications.clear()
        return
    }
    connectorAuthenticationJob?.cancel()
    connectorAuthenticationJob = scope.launch {
        var connected = withTimeoutOrNull(CONNECTOR_UPDATE_WAIT_MILLIS) {
            mutableState.first { state ->
                state.connectors.any { it.id == connectorId && it.isAccessible }
            }
        } != null
        if (!connected) {
            connected = refreshConnectors(controller, forceReload = true)
                ?.any { it.id == connectorId && it.isAccessible } == true
        }
        if (connected) {
            mutableState.update {
                it.copy(
                    statusMessage = "Integration connected",
                    extensionStatus = if (it.pendingPluginSetups.isEmpty()) {
                        ExtensionStatus.INSTALLED
                    } else {
                        it.extensionStatus
                    },
                )
            }
            showExtensionNotice("Integration connected")
            beginNextConnectorAuthentication()
        } else {
            pendingConnectorAuthentications.clear()
            mutableState.update {
                it.copy(
                    statusMessage = "Plugin setup still required",
                )
            }
            showExtensionNotice("Plugin setup still required", isError = true)
        }
    }
}

internal fun AppViewModel.openProviderSettingsAction(pluginId: String) {
    val entry = mutableState.value.providerSettings.singleOrNull { it.pluginId == pluginId }
    if (entry?.activityClassName == null && entry?.removalNeedsRetry == true) {
        scope.launch {
            runCatching { container.platform.finishProviderRemoval(pluginId) }
                .onSuccess {
                    mutableState.update { state ->
                        state.copy(
                            providerSettings = container.platform.providerSettings(),
                            statusMessage = "Provider removed",
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { state ->
                        state.copy(
                            providerSettings = container.platform.providerSettings(),
                            statusMessage = error.message ?: "Provider code removal still needs retry",
                        )
                    }
                }
        }
        return
    }
    runCatching { container.platform.openProviderSettings(pluginId) }
        .onFailure { error ->
            mutableState.update {
                it.copy(statusMessage = error.message ?: "Provider settings are unavailable")
            }
        }
}

internal suspend fun AppViewModel.refreshConnectorsAction(
    controller: CodexSessionController,
    forceReload: Boolean,
): List<AgentConnector>? = connectorRefreshMutex.withLock {
    val refreshedConnectors = runCatching { controller.listConnectors(forceReload) }.getOrNull()
    if (refreshedConnectors != null) {
        mutableState.update { it.copy(connectors = refreshedConnectors) }
        reconcileStoredPluginSetups(refreshedConnectors)
    }
    refreshedConnectors
}

internal fun AppViewModel.setPendingPluginSetupAction(pluginId: String, connectorIds: Set<String>) {
    val normalized = connectorIds.filter(String::isNotBlank).toSet()
    val updated = mutableState.value.pendingPluginSetups.toMutableMap().apply {
        if (normalized.isEmpty()) remove(pluginId) else put(pluginId, normalized)
    }.toMap()
    mutableState.update { it.copy(pendingPluginSetups = updated) }
    uiPreferences.savePendingPluginSetups(updated)
}

internal fun AppViewModel.reconcileStoredPluginSetupsAction(
    connectors: List<AgentConnector>,
    installedPluginIds: Set<String>? = null,
) {
    val current = mutableState.value.pendingPluginSetups
    val reconciled = reconcilePendingPluginSetups(current, connectors, installedPluginIds)
    if (reconciled == current) return
    mutableState.update { it.copy(pendingPluginSetups = reconciled) }
    uiPreferences.savePendingPluginSetups(reconciled)
}

internal fun AppViewModel.showExtensionNoticeAction(message: String, isError: Boolean = false) {
    val notice = ExtensionNotice(message, isError)
    extensionNoticeJob?.cancel()
    mutableState.update { it.copy(extensionNotice = notice) }
    extensionNoticeJob = scope.launch {
        delay(EXTENSION_NOTICE_DURATION_MILLIS)
        mutableState.update { state -> state.copy(extensionNotice = state.extensionNotice.afterExpiry(notice)) }
        extensionNoticeJob = null
    }
}

internal fun AppViewModel.cancelExtensionNoticeAction() {
    extensionNoticeJob?.cancel()
    extensionNoticeJob = null
}

internal fun AppViewModel.extensionMutationAction(operationId: String, message: String, block: suspend () -> Unit) {
    mutableState.update {
        it.copy(
            isExtensionMutationLoading = true,
            extensionOperationId = operationId,
            extensionActionError = null,
        )
    }
    scope.launch {
        try {
            block()
            mutableState.update {
                it.copy(isExtensionMutationLoading = false, extensionOperationId = null)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            extensionFailure(error, message)
        }
    }
}

internal fun AppViewModel.extensionFailureAction(error: Throwable, fallback: String = "Extension request failed") {
    val unavailable = error as? AgentPluginUnavailableException
    mutableState.update {
        val message = error.message?.take(300) ?: fallback
        val operationId = it.extensionOperationId
            ?: unavailable?.let { failure -> "plugin:${failure.pluginId}" }
            ?: "extension"
        it.copy(
            isExtensionMutationLoading = false,
            extensionOperationId = null,
            extensionActionError = ExtensionActionError(operationId, message),
            unavailablePluginIds = unavailable?.let { failure ->
                it.unavailablePluginIds + failure.pluginId
            } ?: it.unavailablePluginIds,
        )
    }
    if (unavailable != null) loadPluginCatalog(forceReload = true)
}

internal fun AppViewModel.beginAppAuthenticationAction(connector: AgentConnector) {
    val url = connector.installUrl ?: return
    mutableState.update {
        it.copy(connectorAuthUrl = url, connectorAuthName = connector.id)
    }
}

internal fun AppViewModel.enqueueConnectorAuthenticationAction(connectors: List<AgentConnector>) {
    val known = buildSet {
        mutableState.value.connectorAuthName?.let(::add)
        pendingConnectorAuthentications.mapTo(this, AgentConnector::id)
    }
    connectors
        .filter { !it.isAccessible && it.installUrl != null && it.id !in known }
        .distinctBy(AgentConnector::id)
        .forEach(pendingConnectorAuthentications::addLast)
    if (mutableState.value.connectorAuthUrl == null) beginNextConnectorAuthentication()
}

internal fun AppViewModel.beginNextConnectorAuthenticationAction() {
    pendingConnectorAuthentications.pollFirst()?.let(::beginAppAuthentication)
}
