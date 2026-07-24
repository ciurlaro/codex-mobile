package io.github.ciurlaro.codexmobile.app

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
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.platform.android.ProviderSettingsEntry

data class MainUiState(
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
    val capabilitySearch: String = "",
    val capabilityFilter: CapabilityFilter = CapabilityFilter.ALL,
    val capabilitySection: CapabilitySection = CapabilitySection.INSTALLED,
    val skillsLoaded: Boolean = false,
    val availableSkillsLoaded: Boolean = false,
    val installedPluginsLoaded: Boolean = false,
    val availablePluginsLoaded: Boolean = false,
    val isSkillsLoading: Boolean = false,
    val isAvailableSkillsLoading: Boolean = false,
    val isInstalledPluginsLoading: Boolean = false,
    val isAvailablePluginsLoading: Boolean = false,
    val isSkillSourceLoading: Boolean = false,
    val isCapabilityMutationLoading: Boolean = false,
    val capabilityOperationId: String? = null,
    val capabilityActionError: CapabilityActionError? = null,
    val unavailablePluginIds: Set<String> = emptySet(),
    val isPluginDetailLoading: Boolean = false,
    val skillsError: String? = null,
    val availableSkillsError: String? = null,
    val installedPluginsError: String? = null,
    val availablePluginsError: String? = null,
    val skillSourceError: String? = null,
    val pluginChangesNeedNewChat: Boolean = false,
    val pendingCapabilityRemoval: CapabilityRemoval? = null,
    val connectorAuthUrl: String? = null,
    val connectorAuthName: String? = null,
    val pendingElicitation: AgentElicitation? = null,
    val selectedModel: String? = null,
    val selectedEffort: String? = null,
    val selectedSpeedTier: String? = null,
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.NEVER,
    val isHistoryOpen: Boolean = false,
    val screen: AppScreen = AppScreen.CHAT,
    val capabilitiesReturnScreen: AppScreen = AppScreen.SETTINGS,
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
}

internal fun MainUiState.withSubmittedTurn(
    request: AgentTurnRequest,
    assistantMessageId: String,
    shellCommand: String?,
) = copy(
    statusMessage = if (shellCommand == null) "Thinking" else "Running command",
    messages = messages + ChatMessage(
        id = "user-${request.clientMessageId}",
        role = AgentMessageRole.USER,
        text = request.prompt,
        capabilities = if (shellCommand == null) request.capabilities else emptySet(),
        invocations = if (shellCommand == null) request.invocations else emptyList(),
        model = request.model,
        effort = request.effort,
    ) + ChatMessage(
        id = assistantMessageId,
        role = AgentMessageRole.CODEX,
        text = "",
        isStreaming = true,
        shellCommand = shellCommand,
    ),
    draft = "",
    selectedCapabilities = emptySet(),
    selectedInvocations = emptyList(),
    activeSelector = null,
)

internal fun MainUiState.withNewChat() = copy(
    statusMessage = if (isAuthenticated) "Ready" else statusMessage,
    streamedText = "",
    sessionId = null,
    messages = emptyList(),
    draft = "",
    selectedCapabilities = emptySet(),
    selectedInvocations = emptyList(),
    isHistoryOpen = false,
    screen = AppScreen.CHAT,
    activeSelector = null,
    historySearch = "",
    isConversationLoading = false,
    pluginChangesNeedNewChat = false,
)

internal fun MainUiState.withoutConversation(sessionId: SessionId): MainUiState {
    val remaining = conversations.filterNot { it.sessionId == sessionId }
    val remainingPins = pinnedConversationIds - sessionId.value
    val updated = copy(
        statusMessage = "Conversation deleted",
        conversations = remaining,
        pinnedConversationIds = remainingPins,
    )
    return if (this.sessionId == sessionId) {
        updated.withNewChat().copy(statusMessage = "Conversation deleted")
    } else {
        updated
    }
}

internal fun List<ChatMessage>.withStreamingAssistant(
    assistantMessageId: String,
    text: String,
    isStreaming: Boolean,
    exitCode: Int?,
): List<ChatMessage> = map { message ->
    if (message.id == assistantMessageId) {
        message.copy(text = text, isStreaming = isStreaming, exitCode = exitCode)
    } else {
        message
    }
}.let { updated ->
    if (isStreaming) updated else updated.filterNot {
        it.id == assistantMessageId && it.text.isEmpty() && it.shellCommand == null
    }
}

internal fun MainUiState.selectedModelOrNull(): AgentModel? =
    models.firstOrNull { it.id == selectedModel }

internal fun MainUiState.connectorsNeedingOnUseAuthentication(): List<AgentConnector> {
    val selectedPlugins = selectedInvocations.filterIsInstance<AgentInvocation.Plugin>().mapNotNull { invocation ->
        plugins.firstOrNull { it.reference.uri == invocation.uri }
            ?.takeIf { it.authPolicy == AgentPluginAuthPolicy.ON_USE }
    }
    return connectors.filter { connector ->
        !connector.isAccessible && connector.installUrl != null && selectedPlugins.any { plugin ->
            connector.id.equals(plugin.reference.name, ignoreCase = true) ||
                connector.pluginNames.any {
                    it.equals(plugin.displayName, ignoreCase = true) ||
                        it.equals(plugin.reference.name, ignoreCase = true)
                }
        }
    }
}
