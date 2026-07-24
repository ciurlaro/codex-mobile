package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class BuiltInToolDefinition(
    val pluginId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutation: Boolean = false,
)

data class BuiltInToolCall(
    val threadId: String,
    val turnId: String,
    val callId: String,
    val pluginId: String,
    val tool: String,
    val arguments: JsonObject,
    val workspace: String,
    val argumentsHash: String,
)

data class BuiltInToolResult(
    val content: List<BuiltInToolContent>,
    val success: Boolean,
) {
    companion object {
        fun text(value: String, success: Boolean = true) =
            BuiltInToolResult(listOf(BuiltInToolContent.Text(value)), success)
    }
}

sealed interface BuiltInToolContent {
    data class Text(val value: String) : BuiltInToolContent
    data class Image(val dataUrl: String) : BuiltInToolContent
}

fun interface BuiltInToolDispatcher {
    suspend fun execute(call: BuiltInToolCall): BuiltInToolResult

    fun definitions(): List<BuiltInToolDefinition> = emptyList()

    suspend fun execute(
        call: BuiltInToolCall,
        beforeMutationDispatch: () -> Unit = {},
    ): BuiltInToolResult = execute(call)

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
): JsonArray = buildJsonArray {
    definitions.filter { it.pluginId in enabledPluginIds }.forEach { definition ->
        add(functionTool(definition.name, definition.description, definition.inputSchema))
    }
}

private fun functionTool(name: String, description: String, schema: JsonObject) = buildJsonObject {
    put("type", "function")
    put("name", name)
    put("description", description)
    put("inputSchema", schema)
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
