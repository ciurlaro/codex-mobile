package io.github.ciurlaro.codexmobile.app.presentation.state

import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionActionError
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionRemoval
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentElicitation
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentHookActivity
import io.github.ciurlaro.codexmobile.agent.AgentPlanProgress
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginSummary
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.SessionId

data class AppUiState(
    val statusMessage: String = "Ready to sign in",
    val streamedText: String = "",
    val streamedReasoning: String = "",
    val streamedPlan: String = "",
    val planProgress: AgentPlanProgress? = null,
    val hookActivities: List<AgentHookActivity> = emptyList(),
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
    val installedPlugins: List<AgentPluginSummary> = emptyList(),
    val availablePlugins: List<AgentPluginSummary> = emptyList(),
    val connectors: List<AgentConnector> = emptyList(),
    val mcpServers: List<AgentMcpServer> = emptyList(),
    val extensionSearch: String = "",
    val extensionType: ExtensionType = ExtensionType.PLUGINS,
    val extensionStatus: ExtensionStatus = ExtensionStatus.INSTALLED,
    val skillsLoaded: Boolean = false,
    val pluginCatalogStatus: PluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
    val isSkillsLoading: Boolean = false,
    val isExtensionMutationLoading: Boolean = false,
    val extensionOperationId: String? = null,
    val extensionActionError: ExtensionActionError? = null,
    val extensionNotice: ExtensionNotice? = null,
    val unavailablePluginIds: Set<String> = emptySet(),
    val pendingPluginSetups: Map<String, Set<String>> = emptyMap(),
    val skillsError: String? = null,
    val pluginCatalogError: String? = null,
    val pendingExtensionRemoval: ExtensionRemoval? = null,
    val connectorAuthUrl: String? = null,
    val connectorAuthName: String? = null,
    val pendingElicitation: AgentElicitation? = null,
    val selectedModel: String? = null,
    val selectedEffort: String? = null,
    val selectedSpeedTier: String? = null,
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.AUTO_REVIEW,
    val collaborationMode: AgentCollaborationMode = AgentCollaborationMode.DEFAULT,
    val hooks: List<AgentHook> = emptyList(),
    val hooksWarnings: List<String> = emptyList(),
    val hooksError: String? = null,
    val isHooksLoading: Boolean = false,
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

    val isPluginCatalogLoading: Boolean
        get() = pluginCatalogStatus == PluginCatalogStatus.CONNECTING ||
            pluginCatalogStatus == PluginCatalogStatus.LOADING

    val pluginActionsEnabled: Boolean
        get() = pluginCatalogStatus == PluginCatalogStatus.LIVE

    val pendingPluginIds: Set<String>
        get() = pendingPluginSetups.keys
}
