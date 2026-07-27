package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.provider.api.CodexMobileProvider
import io.github.ciurlaro.codexmobile.provider.api.ProviderContext
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

class ProviderToolDispatcher(
    providers: List<CodexMobileProvider>,
    private val contextFor: (BuiltInToolCall, () -> Unit, () -> Unit) -> ProviderContext,
) : BuiltInToolDispatcher {
    constructor(
        providers: List<CodexMobileProvider>,
        secrets: (String) -> ProviderSecrets = { ProviderSecrets.EMPTY },
    ) : this(providers, { call, active, before ->
        ProviderContext(
            before,
            secrets(call.pluginId),
            call.deadlineEpochMillis,
            active,
        )
    })

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
    ): BuiltInToolResult = execute(call, {}, beforeMutationDispatch)

    override suspend fun execute(
        call: BuiltInToolCall,
        checkActive: () -> Unit,
        beforeMutationDispatch: () -> Unit,
    ): BuiltInToolResult {
        val provider = providersByTool[call.tool] ?: error("Tool provider is unavailable")
        check(provider.descriptor.pluginId == call.pluginId) { "Tool provider does not match the plugin" }
        val context = contextFor(call, checkActive, beforeMutationDispatch)
        context.ensureActive()
        return provider.execute(call, context)
    }

    override suspend fun replay(call: BuiltInToolCall): BuiltInToolResult? {
        val provider = providersByTool[call.tool] ?: error("Tool provider is unavailable")
        check(provider.descriptor.pluginId == call.pluginId) { "Tool provider does not match the plugin" }
        return provider.replay(call, contextFor(call, {}, {}))
    }
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
