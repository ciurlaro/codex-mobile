package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppInfo
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppSummary
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.McpAuthStatus
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.McpElicitationSchema
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.McpServerStatus
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.McpServerElicitationRequestParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.McpServerElicitationRequestParamsForm
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.McpServerElicitationRequestParamsUrl
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.PluginDetail
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.PluginInstallPolicy
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.PluginMarketplaceEntry
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.PluginSummary
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.SkillMetadata
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.SkillScope
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ToolRequestUserInputParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.UserInput
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.UserInputMentionUserInput
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.UserInputSkillUserInput
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentElicitation
import io.github.ciurlaro.codexmobile.core.AgentFormField
import io.github.ciurlaro.codexmobile.core.AgentFormFieldType
import io.github.ciurlaro.codexmobile.core.AgentFormOption
import io.github.ciurlaro.codexmobile.core.AgentFormValue
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentMcpAuthStatus
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginSkill
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillScope
import io.github.ciurlaro.codexmobile.core.SessionId
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun parseElicitation(
    requestId: String,
    params: McpServerElicitationRequestParams,
): AgentElicitation {
    return when (params) {
        is McpServerElicitationRequestParamsForm -> AgentElicitation(
            requestId = requestId,
            serverName = params.serverName,
            sessionId = SessionId(params.threadId),
            message = params.message,
            form = parseForm(params.requestedSchema),
        )
        is McpServerElicitationRequestParamsUrl -> AgentElicitation(
            requestId = requestId,
            serverName = params.serverName,
            sessionId = SessionId(params.threadId),
            message = params.message,
            url = params.url.also(::requireSafeAuthUrl),
        )
        else -> error("Unsupported MCP elicitation mode")
    }
}

internal fun parseUserInputRequest(
    requestId: String,
    params: ToolRequestUserInputParams,
) = AgentElicitation(
    requestId = requestId,
    serverName = "Plan",
    sessionId = SessionId(params.threadId),
    message = "Codex needs your input to continue planning.",
    form = params.questions.map { question ->
        val options = question.options.orEmpty()
        AgentFormField(
            name = question.id,
            title = question.header,
            description = question.question,
            required = true,
            type = if (options.isEmpty()) AgentFormFieldType.STRING else AgentFormFieldType.SINGLE_SELECT,
            options = options.map { option ->
                AgentFormOption(option.label, option.label, option.description)
            },
            allowOther = question.isOther == true,
            secret = question.isSecret == true,
        )
    },
)

internal fun parseForm(schema: McpElicitationSchema): List<AgentFormField> {
    val required = schema.required.orEmpty().toSet()
    return schema.properties.map { (name, raw) ->
        val field = raw.jsonObject
        val type = field.requiredString("type")
        val options = when {
            field["enum"] is JsonArray -> field.requiredArray("enum").map {
                AgentFormOption(it.jsonPrimitive.content)
            }
            field["oneOf"] is JsonArray -> field.requiredArray("oneOf").map {
                it.jsonObject.let { option ->
                    AgentFormOption(option.requiredString("const"), option.requiredString("title"))
                }
            }
            field.optionalObject("items")?.get("enum") is JsonArray ->
                field.requiredObject("items").requiredArray("enum").map {
                    AgentFormOption(it.jsonPrimitive.content)
                }
            field.optionalObject("items")?.get("anyOf") is JsonArray ->
                field.requiredObject("items").requiredArray("anyOf").map {
                    it.jsonObject.let { option ->
                        AgentFormOption(option.requiredString("const"), option.requiredString("title"))
                    }
                }
            else -> emptyList()
        }
        val fieldType = when {
            type == "array" && options.isNotEmpty() -> AgentFormFieldType.MULTI_SELECT
            type == "string" && options.isNotEmpty() -> AgentFormFieldType.SINGLE_SELECT
            type == "string" -> AgentFormFieldType.STRING
            type == "number" -> AgentFormFieldType.NUMBER
            type == "integer" -> AgentFormFieldType.INTEGER
            type == "boolean" -> AgentFormFieldType.BOOLEAN
            else -> error("Unsupported form field type")
        }
        AgentFormField(
            name = name,
            title = field.optionalString("title") ?: name.replace('_', ' ').replaceFirstChar(Char::uppercase),
            description = field.optionalString("description"),
            required = name in required,
            type = fieldType,
            options = options,
            defaultValue = parseDefault(field, fieldType),
            minimum = field["minimum"]?.jsonPrimitive?.doubleOrNull,
            maximum = field["maximum"]?.jsonPrimitive?.doubleOrNull,
        )
    }
}

internal fun parseDefault(field: JsonObject, type: AgentFormFieldType): AgentFormValue? {
    val value = field["default"]?.takeUnless { it is JsonNull } ?: return null
    return when (type) {
        AgentFormFieldType.STRING, AgentFormFieldType.SINGLE_SELECT ->
            value.jsonPrimitive.contentOrNull?.let(AgentFormValue::Text)
        AgentFormFieldType.NUMBER, AgentFormFieldType.INTEGER ->
            value.jsonPrimitive.doubleOrNull?.let(AgentFormValue::Number)
        AgentFormFieldType.BOOLEAN ->
            value.jsonPrimitive.booleanOrNull?.let(AgentFormValue::BooleanValue)
        AgentFormFieldType.MULTI_SELECT ->
            (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.let(AgentFormValue::TextList)
    }
}
