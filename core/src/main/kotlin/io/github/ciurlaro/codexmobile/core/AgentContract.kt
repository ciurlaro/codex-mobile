package io.github.ciurlaro.codexmobile.core

import kotlinx.coroutines.flow.Flow

interface AgentClient : AutoCloseable {
    val events: Flow<AgentEvent>

    suspend fun authenticate()

    suspend fun cancelAuthentication()

    suspend fun signOut()

    suspend fun listModels(): List<AgentModel> = emptyList()

    suspend fun listSessions(): List<AgentConversationSummary> = emptyList()

    suspend fun readSession(sessionId: SessionId): AgentConversation =
        throw UnsupportedOperationException("Conversation history is unavailable")

    suspend fun openSession(previous: SessionId? = null): SessionId

    suspend fun sendPrompt(sessionId: SessionId, prompt: String)

    suspend fun sendTurn(sessionId: SessionId, request: AgentTurnRequest) {
        sendPrompt(sessionId, request.prompt)
    }

    suspend fun cancelTurn(sessionId: SessionId)

    suspend fun submitToolResult(sessionId: SessionId, result: ToolResult)
}

@JvmInline
value class SessionId(val value: String)

data class AgentModel(
    val id: String,
    val displayName: String,
    val description: String,
    val supportedEfforts: List<String>,
    val defaultEffort: String,
    val isDefault: Boolean,
)

data class AgentConversationSummary(
    val sessionId: SessionId,
    val title: String,
    val updatedAtEpochSeconds: Long,
)

data class AgentConversation(
    val summary: AgentConversationSummary,
    val messages: List<AgentMessage>,
)

enum class AgentMessageRole { USER, CODEX }

data class AgentMessage(
    val id: String,
    val clientId: String?,
    val role: AgentMessageRole,
    val text: String,
    val capabilities: Set<AgentCapability> = emptySet(),
)

enum class AgentCapability(
    val id: String,
    val displayLabel: String,
    val icon: String?,
    val promptLabel: String,
) {
    WEB_SEARCH("web_search", "Web search", "🌐", "Use 🌐 Web search"),
}

data class AgentTurnRequest(
    val prompt: String,
    val clientMessageId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val capabilities: Set<AgentCapability> = emptySet(),
)

fun deriveConversationTitle(
    explicitName: String?,
    firstUserText: String,
    maxLength: Int = 80,
): String {
    require(maxLength > 0)
    val title = explicitName?.trim()?.takeIf { it.isNotEmpty() }
        ?: firstUserText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: "New chat"
    return title.take(maxLength).trimEnd()
}

sealed interface AgentEvent {
    data class AuthenticationRequired(
        val signInUrl: String,
    ) : AgentEvent {
        override fun toString(): String = "AuthenticationRequired"
    }

    data object Authenticated : AgentEvent

    data class SessionOpened(
        val sessionId: SessionId,
        val model: String? = null,
        val effort: String? = null,
    ) : AgentEvent

    data class TextDelta(
        val sessionId: SessionId,
        val text: String,
        val itemId: String? = null,
    ) : AgentEvent

    data class ToolRequested(val sessionId: SessionId, val call: ToolCall) : AgentEvent

    data class TurnCompleted(val sessionId: SessionId) : AgentEvent

    data class Failure(
        val sessionId: SessionId?,
        val code: String,
        val message: String,
        val recoverable: Boolean,
    ) : AgentEvent
}
