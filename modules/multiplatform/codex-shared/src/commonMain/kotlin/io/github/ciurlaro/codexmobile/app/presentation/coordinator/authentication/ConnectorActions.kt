package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionActionError
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.afterExpiry
import io.github.ciurlaro.codexmobile.app.presentation.model.reconcilePendingPluginSetups
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionController
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal fun AppViewModel.connectorAuthenticationReturnedAction() {
    val connectorId = mutableState.value.connectorAuthName ?: return
    mutableState.update { it.copy(connectorAuthUrl = null, connectorAuthName = null) }
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
            mutableState.update { it.copy(statusMessage = "Plugin setup still required") }
            showExtensionNotice("Plugin setup still required", isError = true)
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

internal fun AppViewModel.setPendingPluginSetupAction(
    pluginId: String,
    connectorIds: Set<String>,
) {
    val normalized = connectorIds.filter(String::isNotBlank).toSet()
    val updated = mutableState.value.pendingPluginSetups.toMutableMap().apply {
        if (normalized.isEmpty()) remove(pluginId) else put(pluginId, normalized)
    }.toMap()
    mutableState.update { it.copy(pendingPluginSetups = updated) }
    preferenceState = preferenceState.copy(pendingPluginSetups = updated)
    scope.launch { uiPreferences.savePendingPluginSetups(updated) }
}

internal fun AppViewModel.reconcileStoredPluginSetupsAction(
    connectors: List<AgentConnector>,
    installedPluginIds: Set<String>? = null,
) {
    val current = mutableState.value.pendingPluginSetups
    val reconciled = reconcilePendingPluginSetups(current, connectors, installedPluginIds)
    if (reconciled == current) return
    mutableState.update { it.copy(pendingPluginSetups = reconciled) }
    preferenceState = preferenceState.copy(pendingPluginSetups = reconciled)
    scope.launch { uiPreferences.savePendingPluginSetups(reconciled) }
}

internal fun AppViewModel.showExtensionNoticeAction(
    message: String,
    isError: Boolean = false,
) {
    val notice = ExtensionNotice(message, isError)
    extensionNoticeJob?.cancel()
    mutableState.update { it.copy(extensionNotice = notice) }
    extensionNoticeJob = scope.launch {
        delay(EXTENSION_NOTICE_DURATION_MILLIS)
        mutableState.update { state ->
            state.copy(extensionNotice = state.extensionNotice.afterExpiry(notice))
        }
        extensionNoticeJob = null
    }
}

internal fun AppViewModel.cancelExtensionNoticeAction() {
    extensionNoticeJob?.cancel()
    extensionNoticeJob = null
}

internal fun AppViewModel.extensionMutationAction(
    operationId: String,
    message: String,
    block: suspend () -> Unit,
) {
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

internal fun AppViewModel.extensionFailureAction(
    error: Throwable,
    fallback: String = "Extension request failed",
) {
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
    mutableState.update { it.copy(connectorAuthUrl = url, connectorAuthName = connector.id) }
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
    pendingConnectorAuthentications.removeFirstOrNull()?.let(::beginAppAuthentication)
}
