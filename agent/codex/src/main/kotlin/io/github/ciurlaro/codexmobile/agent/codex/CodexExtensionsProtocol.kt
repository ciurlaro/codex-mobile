package io.github.ciurlaro.codexmobile.agent.codex

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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val OFFICIAL_MARKETPLACES = setOf(
    "openai-curated",
    "openai-api-curated",
    "openai-bundled",
)

internal fun parseSkill(item: JsonObject): AgentSkill {
    val interfaceInfo = item.optionalObject("interface")
    val path = item.requiredString("path")
    val dependencies = item.optionalObject("dependencies")?.optionalArray("tools").orEmpty()
        .mapNotNull { it.jsonObject.optionalString("value") }
    return AgentSkill(
        name = item.requiredString("name"),
        displayName = interfaceInfo?.optionalString("displayName")
            ?: item.requiredString("name").replace('-', ' ').replaceFirstChar(Char::uppercase),
        description = interfaceInfo?.optionalString("shortDescription")
            ?: item.optionalString("shortDescription")
            ?: item.requiredText("description"),
        path = path,
        scope = if ("/plugins/" in path) AgentSkillScope.PLUGIN else when (item.requiredString("scope")) {
            "system" -> AgentSkillScope.SYSTEM
            "user" -> AgentSkillScope.USER
            "repo" -> AgentSkillScope.REPO
            "admin" -> AgentSkillScope.ADMIN
            else -> error("Unsupported skill scope")
        },
        enabled = item.requiredBoolean("enabled"),
        brandColor = interfaceInfo?.optionalString("brandColor"),
        dependencies = dependencies,
    )
}

internal fun parsePluginSummary(
    item: JsonObject,
    marketplaceName: String,
    marketplacePath: String?,
): AgentPluginSummary {
    val interfaceInfo = item.optionalObject("interface")
    val name = item.requiredString("name")
    return AgentPluginSummary(
        reference = AgentPluginReference(
            id = item.requiredString("id"),
            name = name,
            marketplaceName = marketplaceName,
            marketplacePath = marketplacePath,
        ),
        displayName = interfaceInfo?.optionalString("displayName")
            ?: name.replace('-', ' ').replaceFirstChar(Char::uppercase),
        description = interfaceInfo?.optionalString("shortDescription")
            ?: interfaceInfo?.optionalString("longDescription").orEmpty(),
        installed = item.requiredBoolean("installed"),
        enabled = item.requiredBoolean("enabled"),
        installPolicy = enumValueOf(item.requiredString("installPolicy")),
        authPolicy = enumValueOf(item.requiredString("authPolicy")),
        available = item.optionalString("availability") != "DISABLED_BY_ADMIN" &&
            item.requiredString("installPolicy") != "NOT_AVAILABLE",
        capabilities = interfaceInfo?.optionalArray("capabilities").orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull },
        brandColor = interfaceInfo?.optionalString("brandColor"),
        privacyPolicyUrl = interfaceInfo?.optionalString("privacyPolicyUrl"),
        termsOfServiceUrl = interfaceInfo?.optionalString("termsOfServiceUrl"),
        websiteUrl = interfaceInfo?.optionalString("websiteUrl"),
    )
}

internal fun parsePluginMarketplaces(result: JsonObject): List<AgentPluginSummary> =
    result.requiredArray("marketplaces").flatMap { rawMarketplace ->
        val marketplace = rawMarketplace.jsonObject
        val name = marketplace.requiredString("name")
        if (name !in OFFICIAL_MARKETPLACES) return@flatMap emptyList()
        val path = marketplace.optionalString("path")
        marketplace.requiredArray("plugins").map {
            parsePluginSummary(it.jsonObject, name, path)
        }
    }

internal fun parsePluginDetail(result: JsonObject): AgentPluginDetail {
    val plugin = result.requiredObject("plugin")
    val marketplaceName = plugin.requiredString("marketplaceName")
    check(marketplaceName in OFFICIAL_MARKETPLACES) { "Plugin is not from an official marketplace" }
    val summary = parsePluginSummary(
        plugin.requiredObject("summary"),
        marketplaceName,
        plugin.optionalString("marketplacePath"),
    )
    return AgentPluginDetail(
        summary = summary,
        description = plugin.optionalString("description") ?: summary.description,
        skills = plugin.requiredArray("skills").map { raw ->
            val skill = raw.jsonObject
            AgentPluginSkill(
                name = skill.requiredString("name"),
                description = skill.requiredText("description"),
                enabled = skill.requiredBoolean("enabled"),
                path = skill.optionalString("path"),
            )
        },
        connectors = plugin.requiredArray("apps").map { parseConnector(it.jsonObject) },
        mcpServers = plugin.requiredArray("mcpServers").mapNotNull { it.jsonPrimitive.contentOrNull },
        hookCount = plugin.requiredArray("hooks").size,
    )
}

internal fun parseConnector(item: JsonObject) = AgentConnector(
    id = item.requiredString("id"),
    name = item.requiredString("name"),
    description = item.optionalString("description").orEmpty(),
    installUrl = item.optionalString("installUrl"),
    isAccessible = item.optionalBoolean("isAccessible") ?: false,
    isEnabled = item.optionalBoolean("isEnabled") ?: true,
    pluginNames = item.optionalArray("pluginDisplayNames").mapNotNull {
        it.jsonPrimitive.contentOrNull
    },
)

internal fun parseMcpServer(item: JsonObject): AgentMcpServer {
    val name = item.requiredString("name")
    return AgentMcpServer(
        name = name,
        displayName = item.optionalObject("serverInfo")?.optionalString("title") ?: name,
        authStatus = when (item.requiredString("authStatus")) {
            "unsupported" -> AgentMcpAuthStatus.UNSUPPORTED
            "notLoggedIn" -> AgentMcpAuthStatus.NOT_LOGGED_IN
            "bearerToken" -> AgentMcpAuthStatus.BEARER_TOKEN
            "oAuth" -> AgentMcpAuthStatus.OAUTH
            else -> error("Unsupported MCP authentication status")
        },
    )
}

internal fun parseElicitation(
    requestId: String,
    params: JsonObject,
): AgentElicitation {
    val common = AgentElicitation(
        requestId = requestId,
        serverName = params.requiredString("serverName"),
        sessionId = SessionId(params.requiredString("threadId")),
        message = params.requiredText("message"),
    )
    return when (params.requiredString("mode")) {
        "form" -> common.copy(form = parseForm(params.requiredObject("requestedSchema")))
        "url" -> common.copy(url = params.requiredString("url").also(::requireSafeAuthUrl))
        else -> error("Unsupported MCP elicitation mode")
    }
}

private fun parseForm(schema: JsonObject): List<AgentFormField> {
    check(schema.requiredString("type") == "object") { "Elicitation schema must be an object" }
    val required = schema.optionalArray("required").mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    return schema.requiredObject("properties").map { (name, raw) ->
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

internal fun invocationInput(invocation: AgentInvocation): JsonObject = buildJsonObject {
    when (invocation) {
        is AgentInvocation.Skill -> {
            put("type", "skill")
            put("name", invocation.name)
            put("path", invocation.path)
        }
        is AgentInvocation.Plugin -> {
            put("type", "mention")
            put("name", invocation.name)
            put("path", invocation.uri)
        }
    }
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
