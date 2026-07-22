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

const val DOCUMENTS_PLUGIN_ID = "documents@codex-mobile"
const val TELEGRAM_PLUGIN_ID = "telegram@codex-mobile"

val BUILT_IN_TOOL_PLUGINS: Map<String, String> = linkedMapOf(
    "documents_read" to DOCUMENTS_PLUGIN_ID,
    "documents_view_pages" to DOCUMENTS_PLUGIN_ID,
    "documents_edit" to DOCUMENTS_PLUGIN_ID,
    "telegram_list_chats" to TELEGRAM_PLUGIN_ID,
    "telegram_list_messages" to TELEGRAM_PLUGIN_ID,
    "telegram_search_messages" to TELEGRAM_PLUGIN_ID,
    "telegram_search_contacts" to TELEGRAM_PLUGIN_ID,
    "telegram_download_media" to TELEGRAM_PLUGIN_ID,
    "telegram_send_text" to TELEGRAM_PLUGIN_ID,
    "telegram_send_file" to TELEGRAM_PLUGIN_ID,
)

val BUILT_IN_MUTATION_TOOLS = setOf(
    "documents_edit",
    "telegram_download_media",
    "telegram_send_text",
    "telegram_send_file",
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

    suspend fun replay(call: BuiltInToolCall): BuiltInToolResult? = null
}

enum class TypedMutationAuthority { DIRECT, USER_APPROVAL, UNAVAILABLE }

fun typedMutationAuthority(preset: AgentApprovalPreset): TypedMutationAuthority = when (preset) {
    AgentApprovalPreset.NEVER -> TypedMutationAuthority.DIRECT
    AgentApprovalPreset.ASK_ME, AgentApprovalPreset.STRICT -> TypedMutationAuthority.USER_APPROVAL
    AgentApprovalPreset.AUTO_REVIEW -> TypedMutationAuthority.UNAVAILABLE
}

fun builtInDynamicTools(enabledPluginIds: Set<String>): JsonArray = buildJsonArray {
    if (DOCUMENTS_PLUGIN_ID in enabledPluginIds) {
        add(functionTool("documents_read", "Read bounded PDF, image, or Office content semantically.", documentsReadSchema()))
        add(functionTool("documents_view_pages", "Render explicitly selected PDF pages or return a selected image.", documentsViewSchema()))
        add(functionTool("documents_edit", "Create or transactionally edit an Office document with closed operations.", documentsEditSchema()))
    }
    if (TELEGRAM_PLUGIN_ID in enabledPluginIds) {
        add(functionTool("telegram_list_chats", "List bounded Telegram chats.", telegramChatsSchema()))
        add(functionTool("telegram_list_messages", "List bounded Telegram messages for one chat.", telegramMessagesSchema()))
        add(functionTool("telegram_search_messages", "Search bounded Telegram messages.", telegramSearchSchema()))
        add(functionTool("telegram_search_contacts", "Search bounded Telegram contacts.", telegramContactsSchema()))
        add(functionTool("telegram_download_media", "Download one Telegram message attachment into the workspace.", telegramDownloadSchema()))
        add(functionTool("telegram_send_text", "Send one Telegram text message with provider retries disabled.", telegramSendTextSchema()))
        add(functionTool("telegram_send_file", "Send one workspace file through Telegram with provider retries disabled.", telegramSendFileSchema()))
    }
}

private fun functionTool(name: String, description: String, schema: JsonObject) = buildJsonObject {
    put("type", "function")
    put("name", name)
    put("description", description)
    put("inputSchema", schema)
}

private fun documentsReadSchema() = objectSchema(
    properties = linkedMapOf(
        "path" to stringSchema(maxLength = 4_096),
        "mode" to enumSchema("auto", "native", "ocr"),
        "request" to enumSchema("text", "outline", "stats", "issues", "element"),
        "selector" to stringSchema(maxLength = 512),
        "pageStart" to integerSchema(1, 100_000),
        "pageCount" to integerSchema(1, 20),
        "maxChars" to integerSchema(1, 200_000),
        "cursor" to stringSchema(maxLength = 512),
    ),
    required = listOf("path"),
)

private fun documentsViewSchema() = objectSchema(
    properties = linkedMapOf(
        "path" to stringSchema(maxLength = 4_096),
        "pages" to buildJsonObject {
            put("type", "array")
            put("items", integerSchema(1, 100_000))
            put("minItems", 1)
            put("maxItems", 4)
            put("uniqueItems", true)
        },
        "dpi" to integerSchema(72, 160),
    ),
    required = listOf("path", "pages"),
)

private fun documentsEditSchema() = objectSchema(
    properties = linkedMapOf(
        "path" to stringSchema(maxLength = 4_096),
        "create" to booleanSchema(),
        "overwrite" to booleanSchema(),
        "expectedSha256" to stringSchema(pattern = "^[a-f0-9]{64}$", maxLength = 64),
        "operations" to buildJsonObject {
            put("type", "array")
            put("minItems", 1)
            put("maxItems", 50)
            put(
                "items",
                buildJsonObject {
                    put(
                        "oneOf",
                        buildJsonArray {
                            add(editOperation("replace_text", linkedMapOf(
                                "elementPath" to stringSchema(maxLength = 512),
                                "oldText" to stringSchema(maxLength = 20_000),
                                "newText" to stringSchema(maxLength = 20_000),
                            ), listOf("elementPath", "oldText", "newText")))
                            add(editOperation("cell_update", linkedMapOf(
                                "sheet" to stringSchema(maxLength = 128),
                                "cell" to stringSchema(pattern = "^[A-Z]{1,3}[1-9][0-9]{0,6}$", maxLength = 10),
                                "value" to scalarSchema(),
                            ), listOf("sheet", "cell", "value")))
                            add(editOperation("append_paragraph", linkedMapOf(
                                "parentPath" to stringSchema(maxLength = 512),
                                "text" to stringSchema(maxLength = 20_000),
                            ), listOf("text")))
                            add(editOperation("add_slide", linkedMapOf(
                                "title" to stringSchema(maxLength = 500),
                                "body" to stringSchema(maxLength = 20_000),
                            ), emptyList()))
                            add(editOperation("remove_element", linkedMapOf(
                                "elementPath" to stringSchema(maxLength = 512),
                            ), listOf("elementPath")))
                        },
                    )
                },
            )
        },
    ),
    required = listOf("path", "operations"),
)

private fun telegramChatsSchema() = objectSchema(
    linkedMapOf(
        "query" to stringSchema(maxLength = 256),
        "limit" to integerSchema(1, 50),
    ),
)

private fun telegramMessagesSchema() = objectSchema(
    linkedMapOf(
        "chat" to stringSchema(maxLength = 256),
        "limit" to integerSchema(1, 100),
        "source" to enumSchema("archive", "live", "both"),
        "beforeId" to integerSchema(1, Long.MAX_VALUE),
        "afterId" to integerSchema(1, Long.MAX_VALUE),
    ),
    listOf("chat"),
)

private fun telegramSearchSchema() = objectSchema(
    linkedMapOf(
        "query" to stringSchema(maxLength = 1_000),
        "chat" to stringSchema(maxLength = 256),
        "limit" to integerSchema(1, 100),
        "source" to enumSchema("archive", "live", "both"),
        "after" to stringSchema(maxLength = 64),
        "before" to stringSchema(maxLength = 64),
    ),
    listOf("query"),
)

private fun telegramContactsSchema() = objectSchema(
    linkedMapOf(
        "query" to stringSchema(maxLength = 256),
        "limit" to integerSchema(1, 50),
    ),
    listOf("query"),
)

private fun telegramDownloadSchema() = objectSchema(
    linkedMapOf(
        "chat" to stringSchema(maxLength = 256),
        "messageId" to integerSchema(1, Long.MAX_VALUE),
        "outputPath" to stringSchema(maxLength = 4_096),
    ),
    listOf("chat", "messageId", "outputPath"),
)

private fun telegramSendTextSchema() = objectSchema(
    linkedMapOf(
        "to" to stringSchema(maxLength = 256),
        "message" to stringSchema(maxLength = 4_096),
        "parseMode" to enumSchema("none", "markdown", "html"),
        "topic" to integerSchema(1, Long.MAX_VALUE),
        "replyTo" to integerSchema(1, Long.MAX_VALUE),
        "silent" to booleanSchema(),
        "disablePreview" to booleanSchema(),
    ),
    listOf("to", "message"),
)

private fun telegramSendFileSchema() = objectSchema(
    linkedMapOf(
        "to" to stringSchema(maxLength = 256),
        "path" to stringSchema(maxLength = 4_096),
        "caption" to stringSchema(maxLength = 1_024),
        "parseMode" to enumSchema("none", "markdown", "html"),
        "topic" to integerSchema(1, Long.MAX_VALUE),
        "replyTo" to integerSchema(1, Long.MAX_VALUE),
        "silent" to booleanSchema(),
        "forceDocument" to booleanSchema(),
    ),
    listOf("to", "path"),
)

private fun editOperation(
    type: String,
    properties: LinkedHashMap<String, JsonObject>,
    required: List<String>,
): JsonObject = objectSchema(
    linkedMapOf("type" to constSchema(type)).apply { putAll(properties) },
    listOf("type") + required,
)

private fun objectSchema(
    properties: LinkedHashMap<String, JsonObject>,
    required: List<String> = emptyList(),
) = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties))
    if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    put("additionalProperties", false)
}

private fun stringSchema(
    pattern: String? = null,
    maxLength: Int,
) = buildJsonObject {
    put("type", "string")
    put("maxLength", maxLength)
    pattern?.let { put("pattern", it) }
}

private fun integerSchema(minimum: Long, maximum: Long) = buildJsonObject {
    put("type", "integer")
    put("minimum", minimum)
    put("maximum", maximum)
}

private fun booleanSchema() = buildJsonObject { put("type", "boolean") }

private fun enumSchema(vararg values: String) = buildJsonObject {
    put("type", "string")
    put("enum", JsonArray(values.map(::JsonPrimitive)))
}

private fun constSchema(value: String) = buildJsonObject {
    put("type", "string")
    put("const", value)
}

private fun scalarSchema() = buildJsonObject {
    put(
        "oneOf",
        buildJsonArray {
            listOf("string", "number", "boolean").forEach { type ->
                add(buildJsonObject { put("type", type) })
            }
        },
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
