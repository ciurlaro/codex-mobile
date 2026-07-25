package io.github.ciurlaro.codexmobile.provider.api

import kotlinx.serialization.json.JsonObject

data class ProviderToolDefinition(
    val pluginId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutation: Boolean = false,
)

data class ProviderCall(
    val threadId: String,
    val turnId: String,
    val callId: String,
    val pluginId: String,
    val tool: String,
    val arguments: JsonObject,
    val workspace: String,
    val argumentsHash: String,
    val deadlineEpochMillis: Long = Long.MAX_VALUE,
)

data class ProviderResult(
    val content: List<ProviderContent>,
    val success: Boolean,
) {
    companion object {
        fun text(value: String, success: Boolean = true) =
            ProviderResult(listOf(ProviderContent.Text(value)), success)
    }
}

enum class ProviderMutationState { PREPARED, DISPATCHED, SUCCEEDED, FAILED, INDETERMINATE }

data class ProviderMutationEntry(
    val state: ProviderMutationState,
    val result: ProviderResult?,
    val beforeHash: String?,
    val afterHash: String?,
)

interface ProviderMutationJournal {
    fun prepare(call: ProviderCall): ProviderMutationEntry?
    fun find(call: ProviderCall): ProviderMutationEntry?
    fun dispatched(call: ProviderCall, beforeHash: String? = null, afterHash: String? = null)
    fun finish(
        call: ProviderCall,
        state: ProviderMutationState,
        result: ProviderResult,
        beforeHash: String? = null,
        afterHash: String? = null,
    )
}

fun interface ProviderWorkspace {
    fun resolve(path: String, mustExist: Boolean): String
}

sealed interface ProviderContent {
    data class Text(val value: String) : ProviderContent
    data class Image(val dataUrl: String) : ProviderContent
}

data class ProviderDescriptor(
    val pluginId: String,
    val implementationVersion: String,
    val tools: List<ProviderToolDefinition>,
    val providerApi: Int = 2,
    val minHostVersionCode: Int = 1,
    val maxHostVersionCode: Int = Int.MAX_VALUE,
    val displayName: String = pluginId.substringBefore('@'),
    val settingsEntryPoint: String? = null,
    val secrets: List<ProviderSecretDefinition> = emptyList(),
    val schemaDigest: String,
) {
    init {
        require(providerApi > 0) { "Provider API must be positive" }
        require(minHostVersionCode > 0 && maxHostVersionCode >= minHostVersionCode) {
            "Provider host version range is invalid"
        }
        require(pluginId.isNotBlank()) { "Provider plugin ID must not be blank" }
        require(implementationVersion.isNotBlank()) { "Provider version must not be blank" }
        require(displayName.isNotBlank()) { "Provider display name must not be blank" }
        require(schemaDigest.matches(Regex("[a-f0-9]{64}"))) { "Provider schema digest is invalid" }
        require(tools.isNotEmpty()) { "Provider must declare at least one tool" }
        require(tools.all { it.pluginId == pluginId }) { "Provider tool plugin IDs must match the provider" }
        require(tools.map(ProviderToolDefinition::name).distinct().size == tools.size) {
            "Provider tool names must be unique"
        }
        require(secrets.map(ProviderSecretDefinition::name).distinct().size == secrets.size) {
            "Provider secret names must be unique"
        }
    }
}

data class ProviderSecretDefinition(
    val name: String,
    val displayName: String,
    val description: String? = null,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "Provider secret name is invalid" }
        require(displayName.isNotBlank() && displayName.length <= 80) { "Provider secret display name is invalid" }
        require(description == null || description.length <= 300) { "Provider secret description is too long" }
    }
}

fun interface ProviderSecrets {
    fun get(name: String): String?

    companion object {
        val EMPTY = ProviderSecrets { null }
    }
}

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
