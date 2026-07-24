package io.github.ciurlaro.codexmobile.app

import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.SessionId

enum class AppScreen { CHAT, SETTINGS, CAPABILITIES }

enum class CapabilityFilter(val label: String) {
    ALL("All"), SKILLS("Skills"), PLUGINS("Plugins"),
}

enum class CapabilitySection(val label: String) {
    INSTALLED("Installed"), DISCOVER("Discover"),
}

sealed interface CapabilityRemoval {
    val displayName: String

    data class Skill(val skill: AgentSkill) : CapabilityRemoval {
        override val displayName: String get() = skill.readableTitle()
    }

    data class Plugin(val plugin: AgentPluginReference, override val displayName: String) : CapabilityRemoval
}

data class CapabilityActionError(val operationId: String, val message: String)

enum class ChatSelector { TAGS, SKILLS, PLUGINS, EFFORT, MODEL, SPEED, APPROVAL }

sealed interface ChatUiEvent {
    data object OpenHistory : ChatUiEvent
    data object CloseHistory : ChatUiEvent
    data object StartNewChat : ChatUiEvent
    data object OpenSettings : ChatUiEvent
    data object CloseSettings : ChatUiEvent
    data class OpenCapabilities(
        val filter: CapabilityFilter = CapabilityFilter.ALL,
        val returnScreen: AppScreen = AppScreen.SETTINGS,
    ) : ChatUiEvent
    data object CloseCapabilities : ChatUiEvent
    data object RefreshCapabilities : ChatUiEvent
    data object CloseSkillDetails : ChatUiEvent
    data object LoadMoreSkillSource : ChatUiEvent
    data object ClosePluginDetails : ChatUiEvent
    data class OpenSelector(val selector: ChatSelector) : ChatUiEvent
    data object DismissSelector : ChatUiEvent
    data object Send : ChatUiEvent
    data object Stop : ChatUiEvent
    data object Authenticate : ChatUiEvent
    data object CancelAuthentication : ChatUiEvent
    data object OpenSignIn : ChatUiEvent
    data object StopBackground : ChatUiEvent
    data object SignOut : ChatUiEvent
    data object ShowPrivacy : ChatUiEvent
    data object ShowIntegrations : ChatUiEvent
    data object ShowEraseConfirmation : ChatUiEvent
    data object SelectScope : ChatUiEvent
    data object ManageStorage : ChatUiEvent
    data object ClearWorkspace : ChatUiEvent
    data class SearchHistory(val query: String) : ChatUiEvent
    data class OpenConversation(val id: SessionId) : ChatUiEvent
    data class TogglePinConversation(val id: SessionId) : ChatUiEvent
    data class RenameConversation(val id: SessionId, val title: String) : ChatUiEvent
    data class DeleteConversation(val id: SessionId) : ChatUiEvent
    data class UpdateDraft(val text: String) : ChatUiEvent
    data class SelectModel(val id: String) : ChatUiEvent
    data class SelectEffort(val effort: String) : ChatUiEvent
    data class SelectSpeed(val tier: String?) : ChatUiEvent
    data class SelectApproval(val preset: AgentApprovalPreset) : ChatUiEvent
    data class SelectCapabilityFilter(val filter: CapabilityFilter) : ChatUiEvent
    data class SelectCapabilitySection(val section: CapabilitySection) : ChatUiEvent
    data class SearchCapabilities(val query: String) : ChatUiEvent
    data class ToggleSkill(val path: String, val enabled: Boolean) : ChatUiEvent
    data class OpenSkill(val skill: AgentSkill) : ChatUiEvent
    data class OpenSkillPackage(val skill: AgentSkillPackage) : ChatUiEvent
    data class OpenGitHubSkill(val url: String) : ChatUiEvent
    data class SelectGitHubSkill(val skill: AgentSkillPackage) : ChatUiEvent
    data object DismissGitHubSkillImport : ChatUiEvent
    data class AddPluginSource(val url: String) : ChatUiEvent
    data object DismissPluginSource : ChatUiEvent
    data class InstallSkill(val skill: AgentSkillPackage) : ChatUiEvent
    data class RequestUninstallSkill(val skill: AgentSkill) : ChatUiEvent
    data class OpenPlugin(val plugin: AgentPluginReference) : ChatUiEvent
    data class InstallPlugin(val plugin: AgentPluginReference) : ChatUiEvent
    data class RequestUninstallPlugin(val plugin: AgentPluginReference, val displayName: String) : ChatUiEvent
    data object ConfirmCapabilityRemoval : ChatUiEvent
    data object DismissCapabilityRemoval : ChatUiEvent
    data class TogglePlugin(val pluginId: String, val enabled: Boolean) : ChatUiEvent
    data class OpenProviderSettings(val pluginId: String) : ChatUiEvent
    data class ConnectApp(val connectorId: String) : ChatUiEvent
    data class ConnectMcp(val serverName: String) : ChatUiEvent
    data class ResolveElicitation(
        val requestId: String,
        val response: AgentElicitationResponse,
    ) : ChatUiEvent
    data class ResolveCodexApproval(
        val requestId: String,
        val decision: AgentApprovalDecision,
    ) : ChatUiEvent
    data class AddCapability(val capability: AgentCapability) : ChatUiEvent
    data class RemoveCapability(val capability: AgentCapability) : ChatUiEvent
    data class AddInvocation(val invocation: AgentInvocation) : ChatUiEvent
    data class RemoveInvocation(val key: String) : ChatUiEvent
}

data class ChatMessage(
    val id: String,
    val role: AgentMessageRole,
    val text: String,
    val capabilities: Set<AgentCapability> = emptySet(),
    val invocations: List<AgentInvocation> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    val isStreaming: Boolean = false,
    val shellCommand: String? = null,
    val exitCode: Int? = null,
)

internal fun String.shellCommandOrNull(): String? =
    takeIf { it.startsWith('!') }?.drop(1)?.trim()

internal fun String.withoutActiveInvocationToken(invocation: AgentInvocation): String {
    val marker = if (invocation is AgentInvocation.Skill) '$' else '@'
    val match = invocationToken.find(this) ?: return this
    if (match.groupValues[1].singleOrNull() != marker) return this
    val markerIndex = match.range.first + match.value.indexOf(marker)
    return removeRange(markerIndex, length).trimEnd()
}

private val invocationToken = Regex("(?:^|\\s)([@${'$'}])([A-Za-z0-9_:-]*)${'$'}")

internal fun MainUiState.suggestedInvocationItems(): List<PromptInvocation> {
    if (draft.startsWith('!')) return emptyList()
    val match = invocationToken.find(draft) ?: return emptyList()
    val marker = match.groupValues[1].single()
    val query = match.groupValues[2]
    val kind = when (marker) {
        '$' -> PromptInvocationKind.SKILL
        '@' -> PromptInvocationKind.PLUGIN
        else -> return emptyList()
    }
    val recentOrder = recentInvocationKeys.withIndex().associate { it.value to it.index }
    return availablePromptInvocations(kind)
        .filter { query.isEmpty() || it.searchableText.contains(query, ignoreCase = true) }
        .sortedWith(compareBy({ recentOrder[it.invocation.key] ?: Int.MAX_VALUE }, { it.title.lowercase() }))
        .take(5)
}

internal fun MainUiState.suggestedInvocations(): List<AgentInvocation> =
    suggestedInvocationItems().map(PromptInvocation::invocation)

private val bareTaskMarker = Regex("""^(\s*)(\[[ xX]])(?=\s|$)""")

internal fun String.normalizeMarkdownTaskLists(): String {
    var fence: Char? = null
    return split('\n').joinToString("\n") { line ->
        val trimmed = line.trimStart()
        val marker = trimmed.firstOrNull()
            ?.takeIf { it == '`' || it == '~' }
            ?.takeIf { candidate -> trimmed.takeWhile { it == candidate }.length >= 3 }
        when {
            marker != null -> {
                if (fence == null) fence = marker
                else if (fence == marker && trimmed.dropWhile { it == marker }.isBlank()) fence = null
                line
            }

            fence == null -> bareTaskMarker.replaceFirst(line, "\$1- \$2")

            else -> line
        }
    }
}

internal data class ConversationGroups(
    val pinned: List<AgentConversationSummary>,
    val recent: List<AgentConversationSummary>,
)

internal fun List<AgentConversationSummary>.groupedByPins(pinnedIds: Set<String>) = ConversationGroups(
    pinned = filter { it.sessionId.value in pinnedIds },
    recent = filterNot { it.sessionId.value in pinnedIds },
)

internal fun AgentMessage.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    role = role,
    text = text,
    capabilities = capabilities,
    invocations = invocations,
)

internal fun effortLabel(value: String): String = when (value.lowercase()) {
    "none" -> "None"
    "minimal" -> "Minimal"
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "xhigh" -> "Extra High"
    "ultra" -> "Ultra"
    else -> value.replaceFirstChar { it.uppercase() }
}
