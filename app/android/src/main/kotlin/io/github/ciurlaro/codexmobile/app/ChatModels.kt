package io.github.ciurlaro.codexmobile.app

import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.SessionId

enum class AppDestination { CHAT, SETTINGS }

enum class ChatPopup { NONE, TAGS, EFFORT, MODEL, SPEED, APPROVAL }

sealed interface ChatUiEvent {
    data object OpenHistory : ChatUiEvent
    data object CloseHistory : ChatUiEvent
    data object FreshChat : ChatUiEvent
    data object OpenSettings : ChatUiEvent
    data object CloseSettings : ChatUiEvent
    data object ShowEffort : ChatUiEvent
    data object ShowModels : ChatUiEvent
    data object ShowSpeed : ChatUiEvent
    data object ShowApproval : ChatUiEvent
    data object ShowTags : ChatUiEvent
    data object DismissPopup : ChatUiEvent
    data object Send : ChatUiEvent
    data object Stop : ChatUiEvent
    data object Authenticate : ChatUiEvent
    data object CancelAuthentication : ChatUiEvent
    data object OpenSignIn : ChatUiEvent
    data object StopBackground : ChatUiEvent
    data object SignOut : ChatUiEvent
    data object ShowPrivacy : ChatUiEvent
    data object ShowIntegrations : ChatUiEvent
    data object DisconnectTelegram : ChatUiEvent
    data object CancelTelegramAuthentication : ChatUiEvent
    data object ShowEraseConfirmation : ChatUiEvent
    data object SelectScope : ChatUiEvent
    data object ManageStorage : ChatUiEvent
    data object ClearWorkspace : ChatUiEvent
    data class SearchHistory(val query: String) : ChatUiEvent
    data class SelectConversation(val id: SessionId) : ChatUiEvent
    data class TogglePinConversation(val id: SessionId) : ChatUiEvent
    data class RenameConversation(val id: SessionId, val title: String) : ChatUiEvent
    data class DeleteConversation(val id: SessionId) : ChatUiEvent
    data class UpdateDraft(val text: String) : ChatUiEvent
    data class SelectModel(val id: String) : ChatUiEvent
    data class SelectEffort(val effort: String) : ChatUiEvent
    data class SelectSpeed(val tier: String?) : ChatUiEvent
    data class SelectApproval(val preset: AgentApprovalPreset) : ChatUiEvent
    data class ConnectTelegram(val phoneNumber: String) : ChatUiEvent
    data class SubmitTelegramAuthentication(val value: String) : ChatUiEvent
    data class ResolveCodexApproval(val requestId: String, val accept: Boolean) : ChatUiEvent
    data class AddCapability(val capability: AgentCapability) : ChatUiEvent
    data class RemoveCapability(val capability: AgentCapability) : ChatUiEvent
}

data class ChatMessage(
    val id: String,
    val role: AgentMessageRole,
    val text: String,
    val capabilities: Set<AgentCapability> = emptySet(),
    val model: String? = null,
    val effort: String? = null,
    val streaming: Boolean = false,
    val shellCommand: String? = null,
    val exitCode: Int? = null,
)

internal fun String.shellCommandOrNull(): String? =
    takeIf { it.startsWith('!') }?.drop(1)?.trim()

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
