package io.github.ciurlaro.codexmobile.app.presentation.event

import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionFilter
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSection
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.SessionId

sealed interface AppUiEvent {
    data object OpenHistory : AppUiEvent
    data object CloseHistory : AppUiEvent
    data object StartNewChat : AppUiEvent
    data object OpenSettings : AppUiEvent
    data object CloseSettings : AppUiEvent
    data class OpenExtensions(
        val filter: ExtensionFilter = ExtensionFilter.ALL,
        val returnScreen: AppScreen = AppScreen.SETTINGS,
    ) : AppUiEvent
    data object CloseExtensions : AppUiEvent
    data object RefreshExtensions : AppUiEvent
    data object CloseSkillDetails : AppUiEvent
    data object LoadMoreSkillSource : AppUiEvent
    data object ClosePluginDetails : AppUiEvent
    data class OpenSelector(val selector: ChatSelector) : AppUiEvent
    data object DismissSelector : AppUiEvent
    data object Send : AppUiEvent
    data object Stop : AppUiEvent
    data object Authenticate : AppUiEvent
    data object CancelAuthentication : AppUiEvent
    data object OpenSignIn : AppUiEvent
    data object StopBackground : AppUiEvent
    data object SignOut : AppUiEvent
    data object ShowPrivacy : AppUiEvent
    data object ShowIntegrations : AppUiEvent
    data object ShowEraseConfirmation : AppUiEvent
    data object SelectScope : AppUiEvent
    data object ManageStorage : AppUiEvent
    data object ClearWorkspace : AppUiEvent
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
    data class SelectExtensionFilter(val filter: ExtensionFilter) : AppUiEvent
    data class SelectExtensionSection(val section: ExtensionSection) : AppUiEvent
    data class SearchExtensions(val query: String) : AppUiEvent
    data class ToggleSkill(val path: String, val enabled: Boolean) : AppUiEvent
    data class OpenSkill(val skill: AgentSkill) : AppUiEvent
    data class OpenSkillPackage(val skill: AgentSkillPackage) : AppUiEvent
    data class OpenGitHubSkill(val url: String) : AppUiEvent
    data class SelectGitHubSkill(val skill: AgentSkillPackage) : AppUiEvent
    data object DismissGitHubSkillImport : AppUiEvent
    data class AddPluginSource(val url: String) : AppUiEvent
    data object DismissPluginSource : AppUiEvent
    data class InstallSkill(val skill: AgentSkillPackage) : AppUiEvent
    data class RequestUninstallSkill(val skill: AgentSkill) : AppUiEvent
    data class OpenPlugin(val plugin: AgentPluginReference) : AppUiEvent
    data class InstallPlugin(val plugin: AgentPluginReference) : AppUiEvent
    data class RequestUninstallPlugin(
        val plugin: AgentPluginReference,
        val displayName: String,
    ) : AppUiEvent
    data object ConfirmExtensionRemoval : AppUiEvent
    data object DismissExtensionRemoval : AppUiEvent
    data class TogglePlugin(val pluginId: String, val enabled: Boolean) : AppUiEvent
    data class OpenProviderSettings(val pluginId: String) : AppUiEvent
    data class ConnectApp(val connectorId: String) : AppUiEvent
    data class ConnectMcp(val serverName: String) : AppUiEvent
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
