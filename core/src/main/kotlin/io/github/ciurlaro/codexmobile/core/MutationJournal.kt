package io.github.ciurlaro.codexmobile.core

@JvmInline
value class MutationRecordId(val value: String)

enum class MutationState {
    PREPARED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,

    ;

    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED

    fun canTransitionTo(next: MutationState): Boolean = when (this) {
        PREPARED -> next == EXECUTING
        EXECUTING -> next == SUCCEEDED || next == FAILED || next == UNKNOWN
        UNKNOWN -> next == SUCCEEDED || next == FAILED
        SUCCEEDED, FAILED -> false
    }
}

enum class MutationRetrySafety {
    NEVER,
    SAFE_AFTER_PROVEN_NO_OP,
}

data class MutationRecord(
    val id: MutationRecordId,
    val callId: ToolCallId,
    val toolName: String,
    val scopeId: ResourceScopeId,
    val planFingerprint: String,
    val recoveryPayload: String,
    val state: MutationState,
    val outcome: String? = null,
    val sequence: Long = 0,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    val acknowledged: Boolean = false,
)

interface MutationJournal {
    suspend fun create(record: MutationRecord)

    suspend fun transition(
        recordId: MutationRecordId,
        expected: MutationState,
        next: MutationState,
        outcome: String? = null,
        acknowledged: Boolean = false,
    )

    suspend fun find(recordId: MutationRecordId): MutationRecord?

    suspend fun unresolved(): List<MutationRecord>

    suspend fun visible(): List<MutationRecord>

    suspend fun acknowledge(recordId: MutationRecordId)

    suspend fun pruneResolved(updatedBeforeMillis: Long): Int
}
