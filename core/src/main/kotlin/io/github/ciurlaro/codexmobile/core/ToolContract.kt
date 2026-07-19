package io.github.ciurlaro.codexmobile.core

@JvmInline
value class ToolCallId(val value: String)

@JvmInline
value class ResourceScopeId(val value: String)

data class ToolCall(
    val id: ToolCallId,
    val name: String,
    val argumentsJson: String,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
)

enum class ToolEffect {
    READ,
    MUTATION,
}

data class ToolPlan(
    val call: ToolCall,
    val scopeId: ResourceScopeId,
    val effect: ToolEffect,
    val summary: String,
    val fingerprint: String,
)

sealed interface ToolResult {
    val callId: ToolCallId

    data class Success(
        override val callId: ToolCallId,
        val outputJson: String,
    ) : ToolResult

    data class Rejected(
        override val callId: ToolCallId,
        val reason: String,
    ) : ToolResult

    data class Failed(
        override val callId: ToolCallId,
        val code: String,
        val message: String,
    ) : ToolResult

    data class Unknown(
        override val callId: ToolCallId,
        val reason: String,
    ) : ToolResult
}

interface DeviceTool {
    val definition: ToolDefinition
    val name: String get() = definition.name
    val effect: ToolEffect

    suspend fun prepare(call: ToolCall, scopeId: ResourceScopeId): ToolPlan

    suspend fun execute(plan: ToolPlan): ToolResult
}

class ToolRejectedException(message: String) : IllegalArgumentException(message)

enum class ApprovalRequirement {
    DENY,
    ALLOW,
    USER,
}

fun interface ApprovalPolicy {
    fun requirement(plan: ToolPlan): ApprovalRequirement
}

data class UserApproval(
    val callId: ToolCallId,
    val planFingerprint: String,
)
