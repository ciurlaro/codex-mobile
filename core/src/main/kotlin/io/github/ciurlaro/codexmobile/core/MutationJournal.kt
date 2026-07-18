package io.github.ciurlaro.codexmobile.core

enum class MutationState {
    PREPARED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
}

data class MutationRecord(
    val callId: ToolCallId,
    val toolName: String,
    val scopeId: ResourceScopeId,
    val planFingerprint: String,
    val recoveryPayload: String,
    val state: MutationState,
)

interface MutationJournal {
    suspend fun create(record: MutationRecord)

    suspend fun transition(
        callId: ToolCallId,
        expected: MutationState,
        next: MutationState,
    )

    suspend fun unresolved(): List<MutationRecord>
}
