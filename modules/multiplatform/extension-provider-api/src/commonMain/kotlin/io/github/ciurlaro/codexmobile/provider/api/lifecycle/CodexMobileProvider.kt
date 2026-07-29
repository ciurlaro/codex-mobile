package io.github.ciurlaro.codexmobile.provider.api

interface CodexMobileProvider {
    val descriptor: ProviderDescriptor

    suspend fun execute(call: ProviderCall, context: ProviderContext): ProviderResult

    suspend fun replay(call: ProviderCall, context: ProviderContext): ProviderResult? = null

    suspend fun prepareUninstall(context: ProviderContext): ProviderRemovalResult = ProviderRemovalResult.ready()
}

class ProviderContext(
    val beforeMutationDispatch: () -> Unit,
    val secrets: ProviderSecrets = ProviderSecrets.EMPTY,
    val workspace: ProviderWorkspace = ProviderWorkspace { _, _ -> error("Provider workspace is unavailable") },
    val mutations: ProviderMutationJournal = UnavailableProviderMutationJournal,
) {
    var deadlineEpochMillis: Long = Long.MAX_VALUE
        private set
    private var activeCheck: () -> Unit = {}

    constructor(
        beforeMutationDispatch: () -> Unit,
        secrets: ProviderSecrets,
        deadlineEpochMillis: Long,
        checkActive: () -> Unit,
    ) : this(beforeMutationDispatch, secrets) {
        this.deadlineEpochMillis = deadlineEpochMillis
        activeCheck = checkActive
    }

    constructor(
        beforeMutationDispatch: () -> Unit,
        secrets: ProviderSecrets,
        workspace: ProviderWorkspace,
        mutations: ProviderMutationJournal,
        deadlineEpochMillis: Long,
        checkActive: () -> Unit,
    ) : this(beforeMutationDispatch, secrets, workspace, mutations) {
        this.deadlineEpochMillis = deadlineEpochMillis
        activeCheck = checkActive
    }

    fun ensureActive() = activeCheck()
}

private object UnavailableProviderMutationJournal : ProviderMutationJournal {
    private fun unavailable(): Nothing = error("Provider mutation journal is unavailable")
    override fun prepare(call: ProviderCall) = unavailable()
    override fun find(call: ProviderCall) = unavailable()
    override fun dispatched(call: ProviderCall, beforeHash: String?, afterHash: String?) = unavailable()
    override fun finish(
        call: ProviderCall,
        state: ProviderMutationState,
        result: ProviderResult,
        beforeHash: String?,
        afterHash: String?,
    ) = unavailable()
}

enum class ProviderRemovalState { READY, RETRY_REQUIRED }

data class ProviderRemovalResult(
    val state: ProviderRemovalState,
    val message: String? = null,
) {
    companion object {
        fun ready(message: String? = null) = ProviderRemovalResult(ProviderRemovalState.READY, message)
        fun retry(message: String) = ProviderRemovalResult(ProviderRemovalState.RETRY_REQUIRED, message)
    }
}
