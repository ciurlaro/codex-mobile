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

    suspend fun renameSession(sessionId: SessionId, name: String) {
        throw UnsupportedOperationException("Conversation rename is unavailable")
    }

    suspend fun deleteSession(sessionId: SessionId) {
        throw UnsupportedOperationException("Conversation deletion is unavailable")
    }

    suspend fun openSession(
        previous: SessionId? = null,
        settings: AgentRuntimeSettings = AgentRuntimeSettings(),
    ): SessionId

    suspend fun sendPrompt(sessionId: SessionId, prompt: String)

    suspend fun sendTurn(sessionId: SessionId, request: AgentTurnRequest) {
        sendPrompt(sessionId, request.prompt)
    }

    suspend fun runShellCommand(sessionId: SessionId, command: String) {
        throw UnsupportedOperationException("Shell commands are unavailable")
    }

    suspend fun cancelTurn(sessionId: SessionId)

    suspend fun resolveApproval(requestId: String, accept: Boolean) = Unit
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
    val serviceTiers: List<AgentServiceTier> = emptyList(),
    val defaultServiceTier: String? = null,
)

data class AgentServiceTier(
    val id: String,
    val name: String,
    val description: String,
)

enum class AgentApprovalPreset(
    val displayName: String,
    val approvalPolicy: String,
    val approvalsReviewer: String,
) {
    NEVER("Never", "never", "user"),
    AUTO_REVIEW("Auto review", "on-request", "auto_review"),
    ASK_ME("Ask me", "on-request", "user"),
    STRICT("Strict", "untrusted", "user"),
}

data class AgentRuntimeSettings(
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.NEVER,
    val serviceTier: String? = null,
    val workingDirectory: String? = null,
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
    val serviceTier: String? = null,
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.NEVER,
    val capabilities: Set<AgentCapability> = emptySet(),
    val workingDirectory: String? = null,
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
        val serviceTier: String? = null,
    ) : AgentEvent

    data class TextDelta(
        val sessionId: SessionId,
        val text: String,
        val itemId: String? = null,
    ) : AgentEvent

    data class ShellOutputDelta(
        val sessionId: SessionId,
        val text: String,
    ) : AgentEvent

    data class ShellCommandCompleted(
        val sessionId: SessionId,
        val exitCode: Int?,
    ) : AgentEvent

    data class ApprovalRequested(
        val sessionId: SessionId,
        val requestId: String,
        val title: String,
        val details: String,
    ) : AgentEvent

    data class WorkActivityChanged(
        val sessionId: SessionId,
        val activity: AgentWorkActivity?,
    ) : AgentEvent

    data class TurnCompleted(val sessionId: SessionId) : AgentEvent

    data class Failure(
        val sessionId: SessionId?,
        val code: String,
        val message: String,
        val recoverable: Boolean,
    ) : AgentEvent
}

enum class AgentWorkActivity {
    RUNNING_COMMAND,
    READING_FILES,
    WRITING_FILES,
}
