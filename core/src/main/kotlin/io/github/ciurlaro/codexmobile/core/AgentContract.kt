package io.github.ciurlaro.codexmobile.core

import kotlinx.coroutines.flow.Flow

interface AgentClient : AutoCloseable {
    val events: Flow<AgentEvent>

    suspend fun authenticate()

    suspend fun cancelAuthentication()

    suspend fun openSession(previous: SessionId? = null): SessionId

    suspend fun sendPrompt(sessionId: SessionId, prompt: String)

    suspend fun cancelTurn(sessionId: SessionId)

    suspend fun submitToolResult(sessionId: SessionId, result: ToolResult)
}

@JvmInline
value class SessionId(val value: String)

sealed interface AgentEvent {
    data class AuthenticationRequired(
        val verificationUrl: String,
        val userCode: String?,
    ) : AgentEvent

    data object Authenticated : AgentEvent

    data class SessionOpened(val sessionId: SessionId) : AgentEvent

    data class TextDelta(val sessionId: SessionId, val text: String) : AgentEvent

    data class ToolRequested(val sessionId: SessionId, val call: ToolCall) : AgentEvent

    data class TurnCompleted(val sessionId: SessionId) : AgentEvent

    data class Failure(
        val sessionId: SessionId?,
        val code: String,
        val message: String,
        val recoverable: Boolean,
    ) : AgentEvent
}
