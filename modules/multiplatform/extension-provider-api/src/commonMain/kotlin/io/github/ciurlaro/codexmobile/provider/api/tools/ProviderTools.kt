package io.github.ciurlaro.codexmobile.provider.api

import kotlinx.serialization.json.JsonObject

data class ProviderToolDefinition(
    val pluginId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutation: Boolean = false,
)

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
