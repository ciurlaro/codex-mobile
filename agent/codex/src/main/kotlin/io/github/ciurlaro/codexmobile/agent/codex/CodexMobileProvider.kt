package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import kotlinx.serialization.json.put

data class ProviderDescriptor(
    val pluginId: String,
    val implementationVersion: String,
    val tools: List<BuiltInToolDefinition>,
    val providerApi: Int = 1,
    val minHostVersionCode: Int = 1,
    val maxHostVersionCode: Int = Int.MAX_VALUE,
    val displayName: String = pluginId.substringBefore('@'),
    val settingsEntryPoint: String? = null,
    val schemaDigest: String = providerSchemaDigest(tools),
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
        require(tools.map(BuiltInToolDefinition::name).distinct().size == tools.size) {
            "Provider tool names must be unique"
        }
    }
}

interface CodexMobileProvider {
    val descriptor: ProviderDescriptor

    suspend fun execute(
        call: ProviderCall,
        context: ProviderContext,
    ): ProviderResult

    suspend fun replay(call: ProviderCall): ProviderResult? = null

    suspend fun prepareUninstall(): ProviderRemovalResult = ProviderRemovalResult.ready()
}

typealias ProviderCall = BuiltInToolCall
typealias ProviderResult = BuiltInToolResult
typealias ProviderContent = BuiltInToolContent

class ProviderContext(
    val beforeMutationDispatch: () -> Unit,
)

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

enum class ProviderInstallDisposition { NOT_REQUIRED, READY, RESTART_REQUIRED }

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

class ProviderToolDispatcher(
    providers: List<CodexMobileProvider>,
) : BuiltInToolDispatcher {
    private val providersByPlugin = providers.associateBy { it.descriptor.pluginId }.also {
        require(it.size == providers.size) { "Provider plugin IDs must be unique" }
    }
    private val providersByTool = providers.flatMap { provider ->
        provider.descriptor.tools.map { it.name to provider }
    }.toMap().also { tools ->
        require(tools.size == providers.sumOf { it.descriptor.tools.size }) {
            "Provider tool names must be unique"
        }
    }

    override fun definitions(): List<BuiltInToolDefinition> =
        providersByPlugin.values.flatMap { it.descriptor.tools }

    override suspend fun execute(call: BuiltInToolCall): BuiltInToolResult = execute(call) {}

    override suspend fun execute(
        call: BuiltInToolCall,
        beforeMutationDispatch: () -> Unit,
    ): BuiltInToolResult {
        val provider = providersByTool[call.tool] ?: error("Tool provider is unavailable")
        check(provider.descriptor.pluginId == call.pluginId) { "Tool provider does not match the plugin" }
        return provider.execute(call, ProviderContext(beforeMutationDispatch))
    }

    override suspend fun replay(call: BuiltInToolCall): BuiltInToolResult? {
        val provider = providersByTool[call.tool] ?: error("Tool provider is unavailable")
        check(provider.descriptor.pluginId == call.pluginId) { "Tool provider does not match the plugin" }
        return provider.replay(call)
    }
}

fun providerSchemaDigest(tools: List<BuiltInToolDefinition>): String = sha256(
    tools.sortedBy(BuiltInToolDefinition::name).joinToString("\n") {
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
