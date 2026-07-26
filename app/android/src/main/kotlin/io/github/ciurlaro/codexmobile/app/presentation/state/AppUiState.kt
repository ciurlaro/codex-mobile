package io.github.ciurlaro.codexmobile.app.presentation.state

import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionActionError
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionFilter
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionRemoval
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSection
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginSourceUi
import io.github.ciurlaro.codexmobile.app.presentation.model.pluginSourceItems
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentElicitation
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.platform.android.ProviderSettingsEntry

data class AppUiState(
    val statusMessage: String = "Ready to sign in",
    val streamedText: String = "",
    val sessionId: SessionId? = null,
    val isAuthenticated: Boolean = false,
    val conversations: List<AgentConversationSummary> = emptyList(),
    val pinnedConversationIds: Set<String> = emptySet(),
    val messages: List<ChatMessage> = emptyList(),
    val models: List<AgentModel> = emptyList(),
    val draft: String = "",
    val selectedCapabilities: Set<AgentCapability> = emptySet(),
    val selectedInvocations: List<AgentInvocation> = emptyList(),
    val recentInvocationKeys: List<String> = emptyList(),
    val skills: List<AgentSkill> = emptyList(),
    val availableSkills: List<AgentSkillPackage> = emptyList(),
    val installedPlugins: List<AgentPluginSummary> = emptyList(),
    val availablePlugins: List<AgentPluginSummary> = emptyList(),
    val connectors: List<AgentConnector> = emptyList(),
    val mcpServers: List<AgentMcpServer> = emptyList(),
    val providerSettings: List<ProviderSettingsEntry> = emptyList(),
    val selectedSkill: AgentSkill? = null,
    val selectedSkillPackage: AgentSkillPackage? = null,
    val githubSkillCandidates: List<AgentSkillPackage> = emptyList(),
    val githubSkillError: String? = null,
    val isGitHubSkillLoading: Boolean = false,
    val pluginSourceError: String? = null,
    val isPluginSourceLoading: Boolean = false,
    val skillSourceChunks: List<String> = emptyList(),
    val skillSourceNextOffset: Long? = null,
    val skillSourceTotalBytes: Long = 0,
    val selectedPlugin: AgentPluginDetail? = null,
    val extensionSearch: String = "",
    val extensionFilter: ExtensionFilter = ExtensionFilter.ALL,
    val extensionSection: ExtensionSection = ExtensionSection.INSTALLED,
    val skillsLoaded: Boolean = false,
    val availableSkillsLoaded: Boolean = false,
    val installedPluginsLoaded: Boolean = false,
    val availablePluginsLoaded: Boolean = false,
    val isSkillsLoading: Boolean = false,
    val isAvailableSkillsLoading: Boolean = false,
    val isInstalledPluginsLoading: Boolean = false,
    val isAvailablePluginsLoading: Boolean = false,
    val isSkillSourceLoading: Boolean = false,
    val isExtensionMutationLoading: Boolean = false,
    val extensionOperationId: String? = null,
    val extensionActionError: ExtensionActionError? = null,
    val extensionNotice: ExtensionNotice? = null,
    val extensionSourcesOpen: Boolean = false,
    val knownPluginSourceIds: Set<String> = emptySet(),
    val enabledPluginSourceIds: Set<String> = emptySet(),
    val unavailablePluginIds: Set<String> = emptySet(),
    val isPluginDetailLoading: Boolean = false,
    val skillsError: String? = null,
    val availableSkillsError: String? = null,
    val installedPluginsError: String? = null,
    val availablePluginsError: String? = null,
    val skillSourceError: String? = null,
    val pendingExtensionRemoval: ExtensionRemoval? = null,
    val connectorAuthUrl: String? = null,
    val connectorAuthName: String? = null,
    val pendingElicitation: AgentElicitation? = null,
    val selectedModel: String? = null,
    val selectedEffort: String? = null,
    val selectedSpeedTier: String? = null,
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.NEVER,
    val isHistoryOpen: Boolean = false,
    val screen: AppScreen = AppScreen.CHAT,
    val extensionsReturnScreen: AppScreen = AppScreen.SETTINGS,
    val activeSelector: ChatSelector? = null,
    val historySearch: String = "",
    val isConversationLoading: Boolean = false,
    val signInUrl: String? = null,
    val isAuthenticationInProgress: Boolean = false,
    val isTurnActive: Boolean = false,
    val hasStorageAccess: Boolean = false,
    val workspacePath: String? = null,
    val codexApproval: AgentEvent.ApprovalRequested? = null,
    val isBackgroundActive: Boolean = false,
    val isBackgroundNotificationVisible: Boolean = true,
) {
    val plugins: List<AgentPluginSummary>
        get() = (availablePlugins + installedPlugins)
            .associateBy { it.reference.id }
            .values
            .toList()

    val pluginsError: String?
        get() = listOfNotNull(installedPluginsError, availablePluginsError)
            .distinct()
            .joinToString("\n")
            .ifBlank { null }

    val pluginSources: List<PluginSourceUi>
        get() = pluginSourceItems(PluginSourceSelection(knownPluginSourceIds, enabledPluginSourceIds))
}
