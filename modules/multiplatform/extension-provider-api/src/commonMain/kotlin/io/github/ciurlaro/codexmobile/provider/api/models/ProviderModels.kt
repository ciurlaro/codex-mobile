package io.github.ciurlaro.codexmobile.provider.api

import kotlinx.serialization.json.JsonObject

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
