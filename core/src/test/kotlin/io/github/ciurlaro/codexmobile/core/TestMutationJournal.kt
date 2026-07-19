package io.github.ciurlaro.codexmobile.core

internal class TestMutationJournal : MutationJournal {
    private val records = LinkedHashMap<MutationRecordId, MutationRecord>()
    val events = mutableListOf<String>()
    private var sequence = 0L

    override suspend fun create(record: MutationRecord) = synchronized(this) {
        require(record.state == MutationState.PREPARED)
        check(records.putIfAbsent(record.id, record.copy(sequence = ++sequence)) == null)
        events += "create:${record.callId.value}"
    }

    override suspend fun transition(
        recordId: MutationRecordId,
        expected: MutationState,
        next: MutationState,
        outcome: String?,
        acknowledged: Boolean,
    ) = synchronized(this) {
        require(expected.canTransitionTo(next))
        val current = checkNotNull(records[recordId])
        check(current.state == expected)
        records[recordId] = current.copy(
            state = next,
            outcome = outcome,
            acknowledged = acknowledged,
        )
        events += "transition:${expected.name}:${next.name}"
    }

    override suspend fun find(recordId: MutationRecordId): MutationRecord? =
        synchronized(this) { records[recordId] }

    override suspend fun unresolved(): List<MutationRecord> = synchronized(this) {
        records.values.filter { !it.state.isTerminal }
    }

    override suspend fun visible(): List<MutationRecord> = synchronized(this) {
        records.values.filterNot(MutationRecord::acknowledged)
    }

    override suspend fun acknowledge(recordId: MutationRecordId) = synchronized(this) {
        val current = checkNotNull(records[recordId])
        records[recordId] = current.copy(acknowledged = true)
    }

    override suspend fun pruneResolved(updatedBeforeMillis: Long): Int = synchronized(this) {
        val before = records.size
        records.entries.removeAll { (_, record) ->
            record.state.isTerminal && record.acknowledged &&
                record.updatedAtMillis < updatedBeforeMillis
        }
        before - records.size
    }

    fun snapshot(): List<MutationRecord> = synchronized(this) { records.values.toList() }
}
