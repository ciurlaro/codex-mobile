package io.github.ciurlaro.codexmobile.app

import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.MutationRecordId
import io.github.ciurlaro.codexmobile.core.SessionId

enum class AppDestination { CHAT, SETTINGS }

enum class ChatPopup { NONE, TAGS, EFFORT, MODEL }

sealed interface ChatUiEvent {
    data object OpenHistory : ChatUiEvent
    data object CloseHistory : ChatUiEvent
    data object FreshChat : ChatUiEvent
    data object OpenSettings : ChatUiEvent
    data object CloseSettings : ChatUiEvent
    data object ShowEffort : ChatUiEvent
    data object ShowModels : ChatUiEvent
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
    data object ShowEraseConfirmation : ChatUiEvent
    data object SelectScope : ChatUiEvent
    data object SelectMutationScope : ChatUiEvent
    data object SelectExportScope : ChatUiEvent
    data object RevokeScope : ChatUiEvent
    data object RevokeExportScope : ChatUiEvent
    data class SearchHistory(val query: String) : ChatUiEvent
    data class SelectConversation(val id: SessionId) : ChatUiEvent
    data class UpdateDraft(val text: String) : ChatUiEvent
    data class SelectModel(val id: String) : ChatUiEvent
    data class SelectEffort(val effort: String) : ChatUiEvent
    data class AddCapability(val capability: AgentCapability) : ChatUiEvent
    data class RemoveCapability(val capability: AgentCapability) : ChatUiEvent
    data class AcknowledgeMutation(val id: MutationRecordId) : ChatUiEvent
}

data class ChatMessage(
    val id: String,
    val role: AgentMessageRole,
    val text: String,
    val capabilities: Set<AgentCapability> = emptySet(),
    val model: String? = null,
    val effort: String? = null,
    val streaming: Boolean = false,
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

internal fun selectedTagQuery(text: String): String? {
    val token = text.substringAfterLast(' ', text).substringAfterLast('\n')
    return token.takeIf { it.startsWith('@') }?.drop(1)
}

internal fun removeSelectedTagQuery(text: String): String {
    val at = text.indexOfLast { it == '@' }
    if (at < 0 || text.substring(at).any(Char::isWhitespace)) return text
    return text.removeRange(at, text.length).trimEnd()
}
