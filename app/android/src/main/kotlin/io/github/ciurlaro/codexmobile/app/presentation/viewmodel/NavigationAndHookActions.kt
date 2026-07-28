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


internal fun AppViewModel.openSettingsAction() {
    mutableState.update {
        it.copy(
            screen = AppScreen.SETTINGS,
            isHistoryOpen = false,
            activeSelector = null,
            providerSettings = container.platform.providerSettings(),
        )
    }
}

internal fun AppViewModel.closeSettingsAction() {
    mutableState.update { it.copy(screen = AppScreen.CHAT, activeSelector = null) }
}

internal fun AppViewModel.openHooksAction() {
    mutableState.update { it.copy(screen = AppScreen.HOOKS, activeSelector = null) }
    loadHooks()
}

internal fun AppViewModel.closeHooksAction() {
    mutableState.update { it.copy(screen = AppScreen.SETTINGS) }
}

internal fun AppViewModel.refreshHooksAction() = loadHooks()

internal fun AppViewModel.setHookEnabledAction(hook: AgentHook, enabled: Boolean) {
    if (hook.isManaged) return
    val controller = serviceController ?: return
    mutableState.update { it.copy(isHooksLoading = true, hooksError = null) }
    scope.launch {
        runCatching { controller.setHookEnabled(hook.key, enabled) }
            .onSuccess { loadHooks() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(
                        isHooksLoading = false,
                        hooksError = error.message?.take(300) ?: "Hook could not be updated",
                    )
                }
            }
    }
}

internal fun AppViewModel.trustHookAction(hook: AgentHook) {
    if (hook.isManaged) return
    val controller = serviceController ?: return
    mutableState.update { it.copy(isHooksLoading = true, hooksError = null) }
    scope.launch {
        runCatching { controller.trustHook(hook.key, hook.currentHash) }
            .onSuccess { loadHooks() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(
                        isHooksLoading = false,
                        hooksError = error.message?.take(300) ?: "Hook could not be trusted",
                    )
                }
            }
    }
}

internal fun AppViewModel.loadHooksAction() {
    val controller = serviceController
    val workingDirectory = container.platform.activeWorkspacePath()
    if (controller == null || workingDirectory == null) {
        mutableState.update {
            it.copy(isHooksLoading = false, hooksError = "Select a workspace and sign in to load hooks")
        }
        return
    }
    mutableState.update { it.copy(isHooksLoading = true, hooksError = null) }
    scope.launch {
        runCatching { controller.listHooks(workingDirectory) }
            .onSuccess { catalog ->
                mutableState.update {
                    it.copy(
                        hooks = catalog.hooks,
                        hooksWarnings = catalog.warnings,
                        hooksError = catalog.errors.joinToString("\n").ifBlank { null },
                        isHooksLoading = false,
                    )
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(
                        hooksError = error.message?.take(300) ?: "Hooks could not be loaded",
                        isHooksLoading = false,
                    )
                }
            }
    }
}

internal fun AppViewModel.resolveElicitationAction(requestId: String, response: AgentElicitationResponse) {
    serviceController?.resolveElicitation(requestId, response)
}

internal fun AppViewModel.openSelectorAction(selector: ChatSelector) {
    mutableState.update { it.copy(activeSelector = selector) }
}

internal fun AppViewModel.dismissSelectorAction() {
    mutableState.update { it.copy(activeSelector = null) }
}

internal fun AppViewModel.selectModelAction(modelId: String) {
    val model = mutableState.value.models.firstOrNull { it.id == modelId } ?: return
    mutableState.update {
        val effort = it.selectedEffort?.takeIf(model.supportedEfforts::contains)
            ?: model.defaultEffort
        val tier = it.selectedSpeedTier?.takeIf { selected ->
            model.serviceTiers.any { option -> option.id == selected }
        } ?: model.defaultServiceTier
        it.copy(
            selectedModel = model.id,
            selectedEffort = effort,
            selectedSpeedTier = tier,
            activeSelector = ChatSelector.EFFORT,
        )
    }
    persistSelection()
}

internal fun AppViewModel.selectEffortAction(effort: String) {
    val current = mutableState.value
    val model = current.selectedModelOrNull() ?: return
    if (effort !in model.supportedEfforts) return
    mutableState.update { it.copy(selectedEffort = effort, activeSelector = null) }
    persistSelection()
}

internal fun AppViewModel.selectSpeedAction(tier: String?) {
    val current = mutableState.value
    val model = current.selectedModelOrNull() ?: return
    if (tier != null && model.serviceTiers.none { it.id == tier }) return
    mutableState.update { it.copy(selectedSpeedTier = tier, activeSelector = null) }
    persistSelection()
}

internal fun AppViewModel.selectApprovalAction(preset: AgentApprovalPreset) {
    mutableState.update { it.copy(approvalPreset = preset, activeSelector = null) }
    persistSelection()
}

internal fun AppViewModel.resolveCodexApprovalAction(requestId: String, decision: AgentApprovalDecision) {
    serviceController?.resolveApproval(requestId, decision)
}

internal fun AppViewModel.addCapabilityAction(capability: AgentCapability) {
    mutableState.update {
        it.copy(
            selectedCapabilities = it.selectedCapabilities + capability,
            activeSelector = null,
        )
    }
}

internal fun AppViewModel.addInvocationAction(invocation: AgentInvocation) {
    mutableState.update {
        it.copy(
            selectedInvocations = (it.selectedInvocations + invocation)
                .distinctBy(AgentInvocation::key),
            recentInvocationKeys = it.recentInvocationKeys.withRecentInvocation(invocation.key),
            draft = it.draft.withoutActiveInvocationToken(invocation),
            activeSelector = null,
        )
    }
    uiPreferences.saveRecentInvocationKeys(mutableState.value.recentInvocationKeys)
    if (invocation is AgentInvocation.Plugin && !integrationsLoaded) {
        integrationsLoaded = true
        serviceController?.let { controller ->
            scope.launch {
                refreshConnectors(controller, forceReload = false)
                beginOnUseAuthentication(mutableState.value)
            }
        }
    } else {
        beginOnUseAuthentication(mutableState.value)
    }
}

internal fun AppViewModel.removeInvocationAction(key: String) {
    mutableState.update {
        it.copy(selectedInvocations = it.selectedInvocations.filterNot { invocation -> invocation.key == key })
    }
}

internal fun AppViewModel.removeCapabilityAction(capability: AgentCapability) {
    mutableState.update { it.copy(selectedCapabilities = it.selectedCapabilities - capability) }
}

internal fun AppViewModel.persistSelectionAction() {
    val current = mutableState.value
    uiPreferences.saveSelection(
        current.selectedModel,
        current.selectedEffort,
        current.selectedSpeedTier,
        current.approvalPreset,
    )
}
