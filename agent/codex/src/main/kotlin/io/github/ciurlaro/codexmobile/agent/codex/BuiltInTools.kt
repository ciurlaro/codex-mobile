package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.DynamicToolSpec
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.DynamicToolSpecFunctionDynamicToolSpec
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.provider.api.ProviderCall
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent
import io.github.ciurlaro.codexmobile.provider.api.ProviderResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderToolDefinition
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

typealias BuiltInToolDefinition = ProviderToolDefinition
typealias BuiltInToolCall = ProviderCall
typealias BuiltInToolResult = ProviderResult
typealias BuiltInToolContent = ProviderContent

fun interface BuiltInToolDispatcher {
    suspend fun execute(call: BuiltInToolCall): BuiltInToolResult

    fun definitions(): List<BuiltInToolDefinition> = emptyList()

    suspend fun execute(
        call: BuiltInToolCall,
        beforeMutationDispatch: () -> Unit = {},
    ): BuiltInToolResult = execute(call)

    suspend fun execute(
        call: BuiltInToolCall,
        checkActive: () -> Unit,
        beforeMutationDispatch: () -> Unit,
    ): BuiltInToolResult = execute(call, beforeMutationDispatch)

    suspend fun replay(call: BuiltInToolCall): BuiltInToolResult? = null
}

enum class TypedMutationAuthority { DIRECT, USER_APPROVAL, UNAVAILABLE }

fun typedMutationAuthority(preset: AgentApprovalPreset): TypedMutationAuthority = when (preset) {
    AgentApprovalPreset.NEVER -> TypedMutationAuthority.DIRECT
    AgentApprovalPreset.ASK_ME, AgentApprovalPreset.STRICT -> TypedMutationAuthority.USER_APPROVAL
    AgentApprovalPreset.AUTO_REVIEW -> TypedMutationAuthority.UNAVAILABLE
}

fun builtInDynamicTools(
    enabledPluginIds: Set<String>,
    definitions: List<BuiltInToolDefinition>,
): List<DynamicToolSpec> = definitions.filter { it.pluginId in enabledPluginIds }.map { definition ->
    DynamicToolSpecFunctionDynamicToolSpec(
        name = definition.name,
        description = definition.description,
        inputSchema = definition.inputSchema,
    )
}

internal fun canonicalJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${JsonPrimitive(key)}:${canonicalJson(item)}"
        }
    is JsonArray -> value.joinToString(prefix = "[", postfix = "]", transform = ::canonicalJson)
    else -> value.toString()
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
