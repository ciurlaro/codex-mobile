package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderSecrets
import io.github.ciurlaro.codexmobile.provider.api.ProviderToolDefinition
import kotlinx.serialization.json.put

interface ProviderSecretStore {
    fun snapshot(): ProviderSecrets
    fun replace(values: Map<String, String>)
    fun clear()
}

enum class ProviderInstallDisposition { NOT_REQUIRED, READY }

interface PluginProviderHost {
    suspend fun install(plugin: AgentPluginReference, mcpServerNames: Set<String>): ProviderInstallDisposition
    fun pendingInstalls(): List<AgentPluginReference> = emptyList()
    fun preparedRemovals(): List<AgentPluginReference> = emptyList()
    fun installCompleted(pluginId: String) = Unit
    fun manages(pluginId: String): Boolean
    fun mcpServerNames(pluginId: String): Set<String> = emptySet()
    suspend fun prepareRemoval(pluginId: String): ProviderRemovalResult
    suspend fun remove(pluginId: String)
}

fun providerSchemaDigest(tools: List<ProviderToolDefinition>): String = sha256(
    tools.sortedBy(ProviderToolDefinition::name).joinToString("\n") {
        canonicalJson(
            kotlinx.serialization.json.buildJsonObject {
                put("pluginId", it.pluginId)
                put("name", it.name)
                put("description", it.description)
                put("inputSchema", it.inputSchema)
                put("mutation", it.mutation)
            },
        )
    },
)
