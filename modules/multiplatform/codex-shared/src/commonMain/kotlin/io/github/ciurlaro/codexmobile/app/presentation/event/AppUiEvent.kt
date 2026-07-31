package io.github.ciurlaro.codexmobile.app.presentation.event

import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.SessionId

sealed interface AppUiEvent {
    data object OpenHistory : AppUiEvent
    data object CloseHistory : AppUiEvent
    data object StartNewChat : AppUiEvent
    data object OpenSettings : AppUiEvent
    data object CloseSettings : AppUiEvent
    data object OpenHooks : AppUiEvent
    data object CloseHooks : AppUiEvent
    data object RefreshHooks : AppUiEvent
    data class ToggleHook(val hook: AgentHook, val enabled: Boolean) : AppUiEvent
    data class TrustHook(val hook: AgentHook) : AppUiEvent
    data class OpenExtensions(
        val type: ExtensionType = ExtensionType.PLUGINS,
        val returnScreen: AppScreen = AppScreen.SETTINGS,
    ) : AppUiEvent
    data object CloseExtensions : AppUiEvent
    data object RefreshExtensions : AppUiEvent
    data class OpenSelector(val selector: ChatSelector) : AppUiEvent
    data object DismissSelector : AppUiEvent
    data object Send : AppUiEvent
    data object TogglePlanMode : AppUiEvent
    data object ProceedWithPlan : AppUiEvent
    data object Stop : AppUiEvent
    data object Authenticate : AppUiEvent
    data object CancelAuthentication : AppUiEvent
    data object OpenSignIn : AppUiEvent
    data object SignOut : AppUiEvent
    data object ShowPrivacy : AppUiEvent
    data object ShowEraseConfirmation : AppUiEvent
    data object SelectScope : AppUiEvent
    data class SearchHistory(val query: String) : AppUiEvent
    data class OpenConversation(val id: SessionId) : AppUiEvent
    data class TogglePinConversation(val id: SessionId) : AppUiEvent
    data class RenameConversation(val id: SessionId, val title: String) : AppUiEvent
    data class DeleteConversation(val id: SessionId) : AppUiEvent
    data class UpdateDraft(val text: String) : AppUiEvent
    data class SelectModel(val id: String) : AppUiEvent
    data class SelectEffort(val effort: String) : AppUiEvent
    data class SelectSpeed(val tier: String?) : AppUiEvent
    data class SelectApproval(val preset: AgentApprovalPreset) : AppUiEvent
    data class SelectExtensionType(val type: ExtensionType) : AppUiEvent
    data class SelectExtensionStatus(val status: ExtensionStatus) : AppUiEvent
    data class SearchExtensions(val query: String) : AppUiEvent
    data class InstallPlugin(val plugin: AgentPluginReference) : AppUiEvent
    data class ConnectPlugin(val plugin: AgentPluginReference) : AppUiEvent
    data class RequestUninstallPlugin(
        val plugin: AgentPluginReference,
        val displayName: String,
    ) : AppUiEvent
    data object ConfirmExtensionRemoval : AppUiEvent
    data object DismissExtensionRemoval : AppUiEvent
    data class ResolveElicitation(
        val requestId: String,
        val response: AgentElicitationResponse,
    ) : AppUiEvent
    data class ResolveCodexApproval(
        val requestId: String,
        val decision: AgentApprovalDecision,
    ) : AppUiEvent
    data class AddCapability(val capability: AgentCapability) : AppUiEvent
    data class RemoveCapability(val capability: AgentCapability) : AppUiEvent
    data class AddInvocation(val invocation: AgentInvocation) : AppUiEvent
    data class RemoveInvocation(val key: String) : AppUiEvent
}
