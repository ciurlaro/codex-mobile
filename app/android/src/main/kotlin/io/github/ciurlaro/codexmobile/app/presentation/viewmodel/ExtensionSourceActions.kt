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


internal fun AppViewModel.openExtensionsAction(type: ExtensionType, returnScreen: AppScreen) {
    cancelExtensionNotice()
    mutableState.update {
        it.copy(
            screen = AppScreen.EXTENSIONS,
            extensionsReturnScreen = returnScreen,
            extensionType = type,
            extensionStatus = ExtensionStatus.INSTALLED,
            extensionSearch = "",
            extensionSourcesOpen = false,
            extensionNotice = null,
            isHistoryOpen = false,
            activeSelector = null,
        )
    }
    if (
        serviceController == null &&
        uiPreferences.hadAuthenticatedSession &&
        !mutableState.value.isAuthenticationInProgress
    ) authenticate()
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
            extensionSourcesOpen = false,
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
    if (mutableState.value.extensionType == ExtensionType.PLUGINS) reconciledPluginSourceIds = emptySet()
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
            extensionStatus = if (type == ExtensionType.SKILLS && it.extensionStatus == ExtensionStatus.SETUP_PENDING) {
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
        it.copy(
            extensionStatus = status,
            extensionSearch = "",
            extensionActionError = null,
        )
    }
    loadCurrentExtensions(forceReload = false)
}

internal fun AppViewModel.searchExtensionsAction(query: String) {
    mutableState.update { it.copy(extensionSearch = query) }
}

internal fun AppViewModel.openExtensionSourcesAction() {
    cancelExtensionNotice()
    mutableState.update { it.copy(extensionSourcesOpen = true, extensionNotice = null) }
}

internal fun AppViewModel.closeExtensionSourcesAction() {
    mutableState.update { it.copy(extensionSourcesOpen = false) }
    if (mutableState.value.extensionStatus != ExtensionStatus.INSTALLED) {
        loadCurrentExtensions(forceReload = false)
    }
}

internal fun AppViewModel.toggleExtensionSourceAction(sourceId: String, enabled: Boolean) {
    val normalized = canonicalPluginSourceId(sourceId)
    val current = mutableState.value
    if (normalized !in current.knownExtensionSourceIds) return
    pluginsJob?.cancel()
    pluginsJob = null
    pluginRefreshPending = false
    reconciledPluginSourceIds -= normalized
    availableSkillsJob?.cancel()
    mutableState.update {
        val enabledIds = if (enabled) it.enabledExtensionSourceIds + normalized
        else it.enabledExtensionSourceIds - normalized
        it.copy(
            enabledExtensionSourceIds = enabledIds,
            availablePlugins = it.availablePlugins.filter { plugin ->
                it.copy(enabledExtensionSourceIds = enabledIds).isPluginMarketplaceEnabled(
                    plugin.reference.marketplaceName,
                )
            },
            availableSkills = emptyList(),
            pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
            availableSkillsLoaded = false,
            pluginCatalogError = null,
            availableSkillsError = null,
        )
    }
    persistExtensionSourceSelection()
    if (current.extensionType == ExtensionType.PLUGINS) loadPluginCatalog(forceReload = true)
}

internal fun AppViewModel.addExtensionSourceAction(url: String) {
    val controller = serviceController ?: run {
        mutableState.update { it.copy(extensionSourceError = "Codex is not ready") }
        return
    }
    if (url.isBlank() || extensionSourceJob?.isActive == true) return
    val normalizedUrl = url.trim().trimEnd('/')
    mutableState.update { it.copy(extensionSourceError = null, isExtensionSourceLoading = true) }
    extensionSourceJob = scope.launch {
        val (skillResult, pluginResult) = coroutineScope {
            val skills = async { runCatching { controller.discoverGitHubSkills(normalizedUrl) } }
            val plugins = async { runCatching { controller.addPluginMarketplace(normalizedUrl) } }
            skills.await() to plugins.await()
        }
        ensureActive()
        (skillResult.exceptionOrNull() as? CancellationException)?.let { throw it }
        (pluginResult.exceptionOrNull() as? CancellationException)?.let { throw it }
        val skills = skillResult.getOrDefault(emptyList())
        val marketplaceName = pluginResult.getOrNull()
        if (skills.isEmpty() && marketplaceName == null) {
            extensionSourceJob = null
            val skillError = skillResult.exceptionOrNull()?.message ?: "no SKILL.md folders found"
            val pluginError = pluginResult.exceptionOrNull()?.message ?: "no plugin marketplace found"
            mutableState.update {
                it.copy(
                    isExtensionSourceLoading = false,
                    extensionSourceError = "No extensions found. Skills: ${skillError.take(120)}. " +
                        "Plugins: ${pluginError.take(120)}.",
                )
            }
            return@launch
        }
        val existing = mutableState.value.customExtensionSources.firstOrNull {
            it.url.equals(normalizedUrl, ignoreCase = true)
        }
        val source = CustomExtensionSource(
            id = existing?.id ?: "github:${UUID.nameUUIDFromBytes(normalizedUrl.lowercase().toByteArray())}",
            url = normalizedUrl,
            marketplaceName = marketplaceName ?: existing?.marketplaceName,
            supportsSkills = skills.isNotEmpty() || existing?.supportsSkills == true && skillResult.isFailure,
            supportsPlugins = marketplaceName != null || existing?.supportsPlugins == true && pluginResult.isFailure,
        )
        val notice = when {
            skills.isNotEmpty() && marketplaceName != null -> "Source added for skills and plugins"
            skills.isNotEmpty() -> "Source added for skills; plugin check failed: " +
                (pluginResult.exceptionOrNull()?.message ?: "no marketplace found").take(120)
            marketplaceName != null -> "Source added for plugins; skill check failed: " +
                (skillResult.exceptionOrNull()?.message ?: "no skills found").take(120)
            else -> "Source settings were preserved"
        }
        extensionSourceJob = null
        mutableState.update {
            it.copy(
                knownExtensionSourceIds = it.knownExtensionSourceIds + source.id,
                enabledExtensionSourceIds = it.enabledExtensionSourceIds + source.id,
                customExtensionSources = it.customExtensionSources.filterNot { item -> item.id == source.id } + source,
                availableSkills = (it.availableSkills + skills).distinctBy(AgentSkillPackage::id),
                availableSkillsLoaded = false,
                pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
                isExtensionSourceLoading = false,
                extensionSourceError = null,
            )
        }
        showExtensionNotice(notice)
        if (marketplaceName != null) reconciledPluginSourceIds += source.id
        persistExtensionSourceSelection()
    }
}

internal fun AppViewModel.dismissExtensionSourceAction() {
    extensionSourceJob?.cancel()
    extensionSourceJob = null
    mutableState.update { it.copy(extensionSourceError = null, isExtensionSourceLoading = false) }
}
