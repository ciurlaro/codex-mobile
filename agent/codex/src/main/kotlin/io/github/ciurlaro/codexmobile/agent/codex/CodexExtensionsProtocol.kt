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

internal fun parseSkill(item: SkillMetadata): AgentSkill {
    val interfaceInfo = item.interface_
    val path = item.path
    return AgentSkill(
        name = item.name,
        displayName = interfaceInfo?.displayName
            ?: item.name.replace('-', ' ').replaceFirstChar(Char::uppercase),
        description = interfaceInfo?.shortDescription
            ?: item.shortDescription
            ?: item.description,
        path = path,
        scope = if ("/plugins/" in path) AgentSkillScope.PLUGIN else when (item.scope) {
            SkillScope.SYSTEM -> AgentSkillScope.SYSTEM
            SkillScope.USER -> AgentSkillScope.USER
            SkillScope.REPO -> AgentSkillScope.REPO
            SkillScope.ADMIN -> AgentSkillScope.ADMIN
        },
        enabled = item.enabled,
        brandColor = interfaceInfo?.brandColor,
        dependencies = item.dependencies?.tools.orEmpty().map { it.value },
    )
}

internal fun parsePluginSummary(
    item: PluginSummary,
    marketplaceName: String,
    marketplacePath: String?,
): AgentPluginSummary {
    val interfaceInfo = item.interface_
    val name = item.name
    return AgentPluginSummary(
        reference = AgentPluginReference(
            id = item.id,
            name = name,
            marketplaceName = marketplaceName,
            marketplacePath = marketplacePath,
        ),
        displayName = interfaceInfo?.displayName
            ?: name.replace('-', ' ').replaceFirstChar(Char::uppercase),
        description = interfaceInfo?.shortDescription ?: interfaceInfo?.longDescription.orEmpty(),
        installed = item.installed,
        enabled = item.enabled,
        installPolicy = enumValueOf(item.installPolicy.name),
        authPolicy = enumValueOf(item.authPolicy.name),
        available = (item.availability as? JsonPrimitive)?.contentOrNull != "DISABLED_BY_ADMIN" &&
            item.installPolicy != PluginInstallPolicy.NOT_AVAILABLE,
        capabilities = interfaceInfo?.capabilities.orEmpty(),
        brandColor = interfaceInfo?.brandColor,
        privacyPolicyUrl = interfaceInfo?.privacyPolicyUrl,
        termsOfServiceUrl = interfaceInfo?.termsOfServiceUrl,
        websiteUrl = interfaceInfo?.websiteUrl,
    )
}

internal fun parsePluginMarketplaces(marketplaces: List<PluginMarketplaceEntry>): List<AgentPluginSummary> =
    marketplaces.flatMap { marketplace ->
        marketplace.plugins.map {
            parsePluginSummary(it, marketplace.name, marketplace.path)
        }
    }

internal fun parsePluginDetail(plugin: PluginDetail): AgentPluginDetail {
    val summary = parsePluginSummary(
        plugin.summary,
        plugin.marketplaceName,
        plugin.marketplacePath,
    )
    return AgentPluginDetail(
        summary = summary,
        description = plugin.description ?: summary.description,
        skills = plugin.skills.map { skill ->
            AgentPluginSkill(
                name = skill.name,
                description = skill.description,
                enabled = skill.enabled,
                path = skill.path,
            )
        },
        connectors = plugin.apps.map(::parseConnector),
        mcpServers = plugin.mcpServers,
        hookCount = plugin.hooks.size,
    )
}

internal fun parseConnector(item: AppInfo) = AgentConnector(
    id = item.id,
    name = item.name,
    description = item.description.orEmpty(),
    installUrl = item.installUrl,
    isAccessible = item.isAccessible ?: false,
    isEnabled = item.isEnabled ?: true,
    pluginNames = item.pluginDisplayNames.orEmpty(),
)

internal fun parseConnector(item: AppSummary) = AgentConnector(
    id = item.id,
    name = item.name,
    description = item.description.orEmpty(),
    installUrl = item.installUrl,
    isAccessible = false,
    isEnabled = true,
    pluginNames = emptyList(),
)

internal fun parseMcpServer(item: McpServerStatus): AgentMcpServer {
    val name = item.name
    return AgentMcpServer(
        name = name,
        displayName = item.serverInfo?.title ?: name,
        authStatus = when (item.authStatus) {
            McpAuthStatus.UNSUPPORTED -> AgentMcpAuthStatus.UNSUPPORTED
            McpAuthStatus.NOT_LOGGED_IN -> AgentMcpAuthStatus.NOT_LOGGED_IN
            McpAuthStatus.BEARER_TOKEN -> AgentMcpAuthStatus.BEARER_TOKEN
            McpAuthStatus.O_AUTH -> AgentMcpAuthStatus.OAUTH
        },
    )
}

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

private fun parseForm(schema: McpElicitationSchema): List<AgentFormField> {
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

private fun parseDefault(field: JsonObject, type: AgentFormFieldType): AgentFormValue? {
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

internal fun invocationInput(invocation: AgentInvocation): UserInput = when (invocation) {
    is AgentInvocation.Skill -> UserInputSkillUserInput(invocation.name, invocation.path)
    is AgentInvocation.Plugin -> UserInputMentionUserInput(invocation.name, invocation.uri)
}

internal fun parseInvocation(item: JsonObject): AgentInvocation? = when (item.optionalString("type")) {
    "skill" -> AgentInvocation.Skill(item.requiredString("name"), item.requiredString("path"))
    "mention" -> item.requiredString("path").takeIf { it.startsWith("plugin://") }
        ?.let { AgentInvocation.Plugin(item.requiredString("name"), it) }
    else -> null
}

internal fun requireSafeAuthUrl(value: String): String {
    val uri = URI(value)
    val secure = uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()
    val loopback = uri.scheme.equals("http", true) &&
        (uri.host.equals("localhost", true) || uri.host == "127.0.0.1" || uri.host == "::1")
    require(secure || loopback) { "Authorization URL is not HTTPS or loopback HTTP" }
    return value
}

internal fun JsonObject.optionalObject(name: String): JsonObject? =
    this[name] as? JsonObject

internal fun JsonObject.optionalBoolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull
