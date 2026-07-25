package io.github.ciurlaro.codexmobile.appserver.generator

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val GENERATOR_VERSION = "2"
private val JSON = Json { prettyPrint = true }

fun main(arguments: Array<String>) {
    require(arguments.size == 9) {
        "Expected schema source, common.rs, thread.rs, turn.rs, canonical schema, descriptor, " +
            "descriptor Kotlin, model Kotlin and provenance paths"
    }
    val schemaSource = File(arguments[0])
    val commonFile = File(arguments[1])
    val threadFile = File(arguments[2])
    val turnFile = File(arguments[3])
    val schemaFile = File(arguments[4])
    val descriptorFile = File(arguments[5])
    val kotlinFile = File(arguments[6])
    val modelsFile = File(arguments[7])
    val provenanceFile = File(arguments[8])
    writeBytes(schemaFile, schemaSource.readBytes())
    val schema = JSON.parseToJsonElement(schemaFile.readText()).jsonObject
    val definitions = addUsedExperimentalFields(
        schema.getValue("definitions").jsonObject,
        threadFile.readText(),
        turnFile.readText(),
    )
    val common = commonFile.readText()

    val clientSource = parseRequestMacro(common, "client_request_definitions")
    val serverSource = parseRequestMacro(common, "server_request_definitions")
    val client = schemaRoutes(definitions, "ClientRequest").map { route ->
        val source = checkNotNull(clientSource[route.method]) {
            "Client route ${route.method} is absent from pinned common.rs"
        }
        check(source.paramsType == route.paramsType) {
            "Client route ${route.method} params disagree: ${route.paramsType} != ${source.paramsType}"
        }
        route.copy(
            responseType = source.responseType,
            serialization = source.serialization,
            experimentalReason = source.experimentalReason,
            inspectParams = source.inspectParams,
        )
    }
    val server = schemaRoutes(definitions, "ServerRequest").map { route ->
        val source = checkNotNull(serverSource[route.method]) {
            "Server route ${route.method} is absent from pinned common.rs"
        }
        check(source.paramsType == route.paramsType) {
            "Server route ${route.method} params disagree: ${route.paramsType} != ${source.paramsType}"
        }
        route.copy(responseType = source.responseType, experimentalReason = source.experimentalReason)
    }
    val notifications = schemaRoutes(definitions, "ServerNotification")
    val clientNotifications = schemaRoutes(definitions, "ClientNotification")
    check(clientNotifications.map(Route::method) == parseClientNotifications(common).map(Route::method)) {
        "Client notification schema disagrees with pinned common.rs"
    }
    val descriptor = buildJsonObject {
        put("formatVersion", 1)
        put("generatorVersion", GENERATOR_VERSION)
        put("schemaSha256", schemaFile.sha256())
        putJsonArray("clientRequests") { client.forEach { add(it.json()) } }
        putJsonArray("serverRequests") { server.forEach { add(it.json()) } }
        putJsonArray("serverNotifications") { notifications.forEach { add(it.json()) } }
        putJsonArray("clientNotifications") { clientNotifications.forEach { add(it.json()) } }
    }
    writeText(descriptorFile, JSON.encodeToString(JsonObject.serializer(), descriptor) + "\n")
    val models = ProtocolModels(definitions)
    writeText(modelsFile, models.render().trimEnd() + "\n")
    writeText(
        kotlinFile,
        renderKotlin(client, server, notifications, clientNotifications, schemaFile.sha256(), models),
    )
    updateProvenance(
        provenanceFile,
        schemaFile,
        threadFile,
        turnFile,
        descriptorFile,
        kotlinFile,
        modelsFile,
    )
}

private fun addUsedExperimentalFields(
    definitions: JsonObject,
    threadSource: String,
    turnSource: String,
): JsonObject {
    check(
        Regex("#\\[experimental\\(\"thread/start\\.dynamicTools\"\\)\\][\\s\\S]{0,400}pub dynamic_tools: Option<Vec<DynamicToolSpec>>")
            .containsMatchIn(threadSource),
    ) { "Pinned thread.rs no longer declares thread/start.dynamicTools as expected" }
    check(
        Regex("#\\[experimental\\(\"turn/steer\\.additionalContext\"\\)\\][\\s\\S]{0,250}pub additional_context: Option<HashMap<String, AdditionalContextEntry>>")
            .containsMatchIn(turnSource),
    ) { "Pinned turn.rs no longer declares turn/steer.additionalContext as expected" }
    val v2 = definitions.getValue("v2").jsonObject
    val augmentedV2 = JsonObject(v2.toMutableMap().apply {
        put(
            "ThreadStartParams",
            getValue("ThreadStartParams").jsonObject.withOptionalProperty(
                "dynamicTools",
                buildJsonObject {
                    put("type", "array")
                    putJsonObject("items") { put("\$ref", "#/definitions/v2/DynamicToolSpec") }
                },
            ),
        )
        put(
            "TurnSteerParams",
            getValue("TurnSteerParams").jsonObject.withOptionalProperty(
                "additionalContext",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("additionalProperties") {
                        put("\$ref", "#/definitions/v2/AdditionalContextEntry")
                    }
                },
            ),
        )
    })
    return JsonObject(definitions + ("v2" to augmentedV2))
}

private fun JsonObject.withOptionalProperty(name: String, schema: JsonObject): JsonObject =
    JsonObject(toMutableMap().apply {
        val properties = getValue("properties").jsonObject
        check(name !in properties) { "$name is already present in the stable schema" }
        put("properties", JsonObject(properties + (name to schema)))
    })

private data class Route(
    val method: String,
    val variant: String,
    val paramsType: String,
    val responseType: String? = null,
    val serialization: String? = null,
    val experimentalReason: String? = null,
    val inspectParams: Boolean = false,
) {
    fun json() = buildJsonObject {
        put("method", method)
        put("variant", variant)
        put("paramsType", paramsType)
        responseType?.let { put("responseType", it) }
        serialization?.let { put("serialization", it) }
        experimentalReason?.let { put("experimentalReason", it) }
        if (inspectParams) put("inspectParams", true)
    }
}

private fun schemaRoutes(definitions: JsonObject, name: String): List<Route> {
    val union = definitions.getValue(name).jsonObject.getValue("oneOf").jsonArray
    return union.map { raw ->
        val definition = raw.jsonObject
        val properties = definition.getValue("properties").jsonObject
        val method = properties.getValue("method").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content
        val params = properties["params"]?.jsonObject?.schemaTypeName() ?: "Unit"
        Route(method, definition.getValue("title").jsonPrimitive.content, params)
    }.also { routes ->
        check(routes.map(Route::method).distinct().size == routes.size) { "$name contains duplicate methods" }
    }.sortedBy(Route::method)
}

private data class RustRoute(
    val paramsType: String,
    val responseType: String,
    val serialization: String?,
    val experimentalReason: String?,
    val inspectParams: Boolean,
)

private fun parseRequestMacro(source: String, name: String): Map<String, RustRoute> {
    val body = macroBody(source, name)
    val entries = mutableMapOf<String, RustRoute>()
    var cursor = 0
    while (cursor < body.length) {
        val open = body.indexOf('{', cursor)
        if (open < 0) break
        val headerStart = body.lastIndexOf(',', open - 1).let { if (it < cursor) cursor else it + 1 }
        val header = body.substring(headerStart, open)
        val match = Regex("([A-Za-z][A-Za-z0-9]*)(?:\\s*=>\\s*\"([^\"]+)\")?\\s*$")
            .find(header)
        if (match == null) {
            cursor = open + 1
            continue
        }
        val close = matchingBrace(body, open)
        val fields = body.substring(open + 1, close)
        val variant = match.groupValues[1]
        val method = match.groupValues[2].ifEmpty { variant.replaceFirstChar(Char::lowercase) }
        val params = checkNotNull(field(fields, "params")) { "$name.$variant params are missing" }.rustType()
        val response = checkNotNull(field(fields, "response")) { "$name.$variant response is missing" }.rustType()
        val experimental = Regex("#\\[experimental\\(\"([^\"]+)\"\\)\\]").find(header)?.groupValues?.get(1)
        check(entries.put(method, RustRoute(
            paramsType = params,
            responseType = response,
            serialization = field(fields, "serialization"),
            experimentalReason = experimental,
            inspectParams = field(fields, "inspect_params") == "true",
        )) == null) { "$name contains duplicate method $method" }
        cursor = close + 1
    }
    return entries
}

private fun parseClientNotifications(source: String): List<Route> {
    val body = macroBody(source, "client_notification_definitions")
    return Regex("[A-Za-z][A-Za-z0-9]*").findAll(body).map { match ->
        val variant = match.value
        Route(variant.replaceFirstChar(Char::lowercase), variant, "Unit")
    }.toList()
}

private fun macroBody(source: String, name: String): String {
    val marker = "$name!"
    val markerIndex = source.indexOf(marker)
    check(markerIndex >= 0) { "$marker is absent from pinned common.rs" }
    val open = source.indexOf('{', markerIndex + marker.length)
    check(open >= 0) { "$marker has no body" }
    return source.substring(open + 1, matchingBrace(source, open))
}

private fun matchingBrace(source: String, open: Int): Int {
    var depth = 0
    var quoted = false
    var escaped = false
    for (index in open until source.length) {
        val char = source[index]
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
            continue
        }
        if (char == '"') quoted = true
        else if (char == '{') depth++
        else if (char == '}' && --depth == 0) return index
    }
    error("Unclosed Rust macro body")
}

private fun field(body: String, name: String): String? = Regex("(?m)^\\s*$name:\\s*(.+),\\s*$")
    .find(body)?.groupValues?.get(1)?.trim()

private fun String.rustType(): String = replace(Regex("#\\[[^]]+]\\s*"), "")
    .removePrefix("v1::")
    .removePrefix("v2::")
    .let { if (it == "()" || it == "Option<()>") "Unit" else it }

private fun JsonObject.schemaTypeName(): String = get("\$ref")?.jsonPrimitive?.content
    ?.removePrefix("#/definitions/")
    ?.substringAfterLast('/')
    ?.also { check(it.isNotBlank()) { "Empty schema reference" } }
    ?: if (get("type")?.jsonPrimitive?.content == "null") "Unit" else error("Route params have no named type")

private fun renderKotlin(
    client: List<Route>,
    server: List<Route>,
    notifications: List<Route>,
    clientNotifications: List<Route>,
    schemaSha256: String,
    models: ProtocolModels,
): String = buildString {
    appendLine("// Generated by protocol-generator $GENERATOR_VERSION. Do not edit.")
    appendLine("package io.github.ciurlaro.codexmobile.appserver.protocol.generated")
    appendLine()
    appendLine("import kotlinx.serialization.KSerializer")
    appendLine("import kotlinx.serialization.builtins.serializer")
    appendLine()
    appendLine("public data class AppServerRequestDescriptor(")
    appendLine("    public val method: String,")
    appendLine("    public val paramsType: String,")
    appendLine("    public val responseType: String,")
    appendLine("    public val serialization: String? = null,")
    appendLine("    public val experimentalReason: String? = null,")
    appendLine("    public val inspectParams: Boolean = false,")
    appendLine(")")
    appendLine()
    appendLine("public data class AppServerNotificationDescriptor(")
    appendLine("    public val method: String,")
    appendLine("    public val paramsType: String,")
    appendLine(")")
    appendLine()
    appendLine("public interface AppServerMethod<P, R> {")
    appendLine("    public val descriptor: AppServerRequestDescriptor")
    appendLine("    public val paramsSerializer: KSerializer<P>")
    appendLine("    public val responseSerializer: KSerializer<R>")
    appendLine("}")
    appendLine()
    appendLine("public object AppServerProtocolDescriptors {")
    appendLine("    public const val SCHEMA_SHA256: String = \"$schemaSha256\"")
    appendRequestMap("clientRequests", client)
    appendRequestMap("serverRequests", server)
    appendNotificationMap("serverNotifications", notifications)
    appendNotificationMap("clientNotifications", clientNotifications)
    appendLine("}")
    appendLine()
    appendMethodObjects("AppServerClientMethods", "clientRequests", client, models)
    appendLine()
    appendMethodObjects("AppServerServerMethods", "serverRequests", server, models)
}

private fun StringBuilder.appendRequestMap(name: String, routes: List<Route>) {
    appendLine("    public val $name: Map<String, AppServerRequestDescriptor> = listOf(")
    routes.forEach { route ->
        append("        AppServerRequestDescriptor(\"").append(route.method.escape()).append("\", \"")
            .append(route.paramsType.escape()).append("\", \"").append(checkNotNull(route.responseType).escape()).append('"')
        route.serialization?.let { append(", serialization = \"").append(it.escape()).append('"') }
        route.experimentalReason?.let { append(", experimentalReason = \"").append(it.escape()).append('"') }
        if (route.inspectParams) append(", inspectParams = true")
        appendLine("),")
    }
    appendLine("    ).associateBy(AppServerRequestDescriptor::method)")
}

private fun StringBuilder.appendNotificationMap(name: String, routes: List<Route>) {
    appendLine("    public val $name: Map<String, AppServerNotificationDescriptor> = listOf(")
    routes.forEach { route ->
        appendLine("        AppServerNotificationDescriptor(\"${route.method.escape()}\", \"${route.paramsType.escape()}\"),")
    }
    appendLine("    ).associateBy(AppServerNotificationDescriptor::method)")
}

private fun StringBuilder.appendMethodObjects(
    objectName: String,
    descriptorMap: String,
    routes: List<Route>,
    models: ProtocolModels,
) {
    appendLine("public object $objectName {")
    routes.forEach { route ->
        val response = checkNotNull(route.responseType)
        appendLine("    public data object ${route.method.kotlinTypeName()} : AppServerMethod<${route.paramsType}, $response> {")
        appendLine("        override val descriptor: AppServerRequestDescriptor =")
        appendLine("            AppServerProtocolDescriptors.$descriptorMap.getValue(\"${route.method.escape()}\")")
        appendLine("        override val paramsSerializer: KSerializer<${route.paramsType}> = ${models.serializer(route.paramsType)}")
        appendLine("        override val responseSerializer: KSerializer<$response> = ${models.serializer(response)}")
        appendLine("    }")
    }
    appendLine("}")
}

private class ProtocolModels(root: JsonObject) {
    private val definitions = linkedMapOf<String, JsonObject>().apply {
        root.forEach { (name, schema) ->
            if (name != "v2") put(name, schema.jsonObject)
        }
        root.getValue("v2").jsonObject.forEach { (name, schema) -> putIfAbsent(name, schema.jsonObject) }
    }
    private val kinds = definitions.mapValues { (_, schema) -> kind(schema) }

    fun serializer(type: String): String = when (type) {
        "Unit" -> "Unit.serializer()"
        else -> when (kinds[type]) {
            ModelKind.JSON -> "kotlinx.serialization.json.JsonElement.serializer()"
            ModelKind.STRING -> "String.serializer()"
            ModelKind.LONG -> "Long.serializer()"
            ModelKind.DOUBLE -> "Double.serializer()"
            ModelKind.BOOLEAN -> "Boolean.serializer()"
            else -> "$type.serializer()"
        }
    }

    fun render(): String = buildString {
        appendLine("// Generated by protocol-generator $GENERATOR_VERSION. Do not edit.")
        appendLine("@file:Suppress(\"unused\")")
        appendLine()
        appendLine("package io.github.ciurlaro.codexmobile.appserver.protocol.generated")
        appendLine()
        appendLine("import kotlinx.serialization.DeserializationStrategy")
        appendLine("import kotlinx.serialization.SerialName")
        appendLine("import kotlinx.serialization.Serializable")
        appendLine("import kotlinx.serialization.json.JsonContentPolymorphicSerializer")
        appendLine("import kotlinx.serialization.json.JsonElement")
        appendLine("import kotlinx.serialization.json.JsonObject")
        appendLine("import kotlinx.serialization.json.jsonObject")
        appendLine("import kotlinx.serialization.json.jsonPrimitive")
        appendLine()
        definitions.toSortedMap().forEach { (name, schema) ->
            append(renderDefinition(name, schema))
            appendLine()
        }
    }

    private fun renderDefinition(name: String, schema: JsonObject): String = when (kind(schema)) {
        ModelKind.ENUM -> renderEnum(name, schema)
        ModelKind.OBJECT -> renderObject(name, schema)
        ModelKind.UNION -> renderUnion(name, schema, checkNotNull(discriminatedUnion(schema)))
        ModelKind.STRING -> "public typealias $name = String\n"
        ModelKind.LONG -> "public typealias $name = Long\n"
        ModelKind.DOUBLE -> "public typealias $name = Double\n"
        ModelKind.BOOLEAN -> "public typealias $name = Boolean\n"
        ModelKind.JSON -> "public typealias $name = JsonElement\n"
    }

    private fun renderEnum(name: String, schema: JsonObject): String = buildString {
        appendLine("@Serializable")
        appendLine("public enum class $name {")
        val used = mutableSetOf<String>()
        schema.getValue("enum").jsonArray.forEachIndexed { index, raw ->
            val value = raw.jsonPrimitive.content
            var entry = value.enumEntryName()
            while (!used.add(entry)) entry = "${entry}_${index + 1}"
            appendLine("    @SerialName(\"${value.escape()}\") $entry,")
        }
        appendLine("}")
    }

    private fun renderObject(
        name: String,
        schema: JsonObject,
        parent: String? = null,
        discriminator: Pair<String, String>? = null,
    ): String {
        if (schema["additionalProperties"]?.toString() == "true" && schema["properties"] != null) {
            return "public typealias $name = JsonObject\n"
        }
        val properties = schema["properties"]?.jsonObject.orEmpty()
        if (properties.isEmpty()) return buildString {
            appendLine("@Serializable")
            append("public class $name")
            parent?.let { append(" : $it") }
            appendLine()
        }
        val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet()
        val fields = properties.map { (wireName, propertySchema) ->
            val forced = discriminator?.takeIf { it.first == wireName }?.second
            val optional = wireName !in required && forced == null
            val rawType = if (forced != null) "String" else (propertySchema as? JsonObject)?.let(::typeOf) ?: "JsonElement"
            Field(wireName, wireName.kotlinIdentifier(), rawType.nullableIf(optional), forced, optional)
        }.sortedBy { it.optional || it.forcedValue != null }
        return buildString {
            appendLine("@Serializable")
            appendLine("public data class $name(")
            fields.forEach { field ->
                appendLine("    @SerialName(\"${field.wireName.escape()}\")")
                append("    public val ${field.identifier}: ${field.type}")
                when {
                    field.forcedValue != null -> append(" = \"${field.forcedValue.escape()}\"")
                    field.optional -> append(" = null")
                }
                appendLine(",")
            }
            append(")")
            parent?.let { append(" : $it") }
            if (discriminator == null) {
                appendLine()
            } else {
                val identifier = discriminator.first.kotlinIdentifier()
                appendLine(" {")
                appendLine("    init { require($identifier == \"${discriminator.second.escape()}\") }")
                appendLine("}")
            }
        }
    }

    private fun renderUnion(
        name: String,
        schema: JsonObject,
        union: DiscriminatedUnion,
    ): String = buildString {
        appendLine("@Serializable(with = ${name}Serializer::class)")
        appendLine("public sealed interface $name")
        appendLine()
        union.variants.forEach { variant ->
            append(
                renderObject(
                    variant.name,
                    mergeUnionConstraints(schema, variant.schema),
                    name,
                    union.property to variant.value,
                ),
            )
            appendLine()
        }
        appendLine("public object ${name}Serializer : JsonContentPolymorphicSerializer<$name>($name::class) {")
        appendLine("    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<$name> =")
        appendLine("        when (element.jsonObject[\"${union.property.escape()}\"]?.jsonPrimitive?.content) {")
        union.variants.forEach { variant ->
            appendLine("            \"${variant.value.escape()}\" -> ${variant.name}.serializer()")
        }
        appendLine("            else -> error(\"Unknown $name ${union.property}\")")
        appendLine("        }")
        appendLine("}")
    }

    private fun mergeUnionConstraints(schema: JsonObject, variant: JsonObject): JsonObject {
        val properties = schema["properties"]?.jsonObject.orEmpty() +
            variant["properties"]?.jsonObject.orEmpty()
        val required = (
            schema["required"]?.jsonArray.orEmpty() +
                variant["required"]?.jsonArray.orEmpty()
            ).distinctBy { it.jsonPrimitive.content }
        return JsonObject(variant.toMutableMap().apply {
            put("properties", JsonObject(properties))
            put("required", JsonArray(required))
        })
    }

    private fun kind(schema: JsonObject): ModelKind {
        if (schema["enum"] is JsonArray && schema["type"]?.toString()?.contains("string") == true) return ModelKind.ENUM
        if (discriminatedUnion(schema) != null) return ModelKind.UNION
        val types = schema.types()
        return when (types.firstOrNull { it != "null" }) {
            "object" -> if (schema["additionalProperties"]?.toString() == "true" && schema["properties"] != null) {
                ModelKind.JSON
            } else {
                ModelKind.OBJECT
            }
            "string" -> ModelKind.STRING
            "integer" -> ModelKind.LONG
            "number" -> ModelKind.DOUBLE
            "boolean" -> ModelKind.BOOLEAN
            else -> ModelKind.JSON
        }
    }

    private fun typeOf(schema: JsonObject): String {
        schema["\$ref"]?.jsonPrimitive?.content?.let { return it.substringAfterLast('/') }
        schema["allOf"]?.jsonArray?.singleOrNull()?.jsonObject?.let { return typeOf(it) }
        val nullableUnion = (schema["anyOf"] ?: schema["oneOf"]) as? JsonArray
        if (nullableUnion != null) {
            val variants = nullableUnion.mapNotNull { it as? JsonObject }
            if (variants.size != nullableUnion.size) return "JsonElement"
            val nonNull = variants.filterNot { it.types() == listOf("null") }
            if (nonNull.size == 1 && nonNull.size < nullableUnion.size) return typeOf(nonNull.single()).nullableIf(true)
            return "JsonElement"
        }
        val types = schema.types()
        val nullable = "null" in types
        val type = when (types.firstOrNull { it != "null" }) {
            "string" -> "String"
            "integer" -> "Long"
            "number" -> "Double"
            "boolean" -> "Boolean"
            "array" -> "List<${(schema["items"] as? JsonObject)?.let(::typeOf) ?: "JsonElement"}>"
            "object" -> {
                val additional = schema["additionalProperties"]
                if (additional is JsonObject) "Map<String, ${typeOf(additional)}>" else "JsonObject"
            }
            else -> "JsonElement"
        }
        return type.nullableIf(nullable)
    }

    private fun discriminatedUnion(schema: JsonObject): DiscriminatedUnion? {
        val raw = (schema["oneOf"] ?: schema["anyOf"]) as? JsonArray ?: return null
        val variants = raw.mapNotNull { it as? JsonObject }
        if (variants.size != raw.size || variants.any { it.types().firstOrNull() != "object" }) return null
        val candidates = variants.map { it["properties"]?.jsonObject?.keys.orEmpty() }
            .reduceOrNull(Set<String>::intersect).orEmpty().sorted()
        val property = candidates.firstOrNull { candidate ->
            val values = variants.mapNotNull {
                (it["properties"]?.jsonObject?.get(candidate) as? JsonObject)?.constantValue()
            }
            values.size == variants.size && values.distinct().size == values.size
        } ?: return null
        return DiscriminatedUnion(
            property,
            variants.map { variant ->
                val value = checkNotNull(
                    (variant["properties"]?.jsonObject?.get(property) as? JsonObject)?.constantValue(),
                )
                val title = (variant["title"]?.jsonPrimitive?.content ?: value)
                    .replace(Regex("v[0-9]+::.*$"), "")
                UnionVariant(name = schemaNamePrefix(schema, title), value = value, schema = variant)
            },
        )
    }

    private fun schemaNamePrefix(schema: JsonObject, title: String): String {
        val owner = definitions.entries.firstOrNull { it.value === schema }?.key.orEmpty()
        return owner + title.kotlinTypeName()
    }

    private data class Field(
        val wireName: String,
        val identifier: String,
        val type: String,
        val forcedValue: String?,
        val optional: Boolean,
    )

    private data class DiscriminatedUnion(val property: String, val variants: List<UnionVariant>)
    private data class UnionVariant(val name: String, val value: String, val schema: JsonObject)
    private enum class ModelKind { OBJECT, ENUM, UNION, STRING, LONG, DOUBLE, BOOLEAN, JSON }
}

private fun JsonObject.types(): List<String> = when (val type = get("type")) {
    is JsonArray -> type.map { it.jsonPrimitive.content }
    is JsonPrimitive -> listOf(type.content)
    else -> emptyList()
}

private fun JsonObject.constantValue(): String? = get("const")?.jsonPrimitive?.content
    ?: (get("enum") as? JsonArray)?.singleOrNull()?.jsonPrimitive?.content

private fun String.nullableIf(nullable: Boolean): String = if (nullable && !endsWith('?')) "$this?" else this

private fun String.kotlinIdentifier(): String {
    val cleaned = replace(Regex("[^A-Za-z0-9_]"), "_").let {
        if (it.firstOrNull()?.isDigit() == true) "value_$it" else it
    }
    return if (cleaned in KOTLIN_KEYWORDS) "${cleaned}_" else cleaned
}

private fun String.enumEntryName(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
    .replace(Regex("[^A-Za-z0-9]+"), "_")
    .trim('_')
    .uppercase()
    .ifEmpty { "EMPTY" }
    .let { if (it.first().isDigit()) "VALUE_$it" else it }

private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)

private fun updateProvenance(
    file: File,
    schema: File,
    thread: File,
    turn: File,
    descriptor: File,
    kotlin: File,
    models: File,
) {
    val current = JSON.parseToJsonElement(file.readText()).jsonObject
    val updated = JsonObject(current.toMutableMap().apply {
        val completeSchemaPath = "codex-rs/app-server-protocol/schema/json/codex_app_server_protocol.schemas.json"
        val threadPath = "codex-rs/app-server-protocol/src/protocol/v2/thread.rs"
        val turnPath = "codex-rs/app-server-protocol/src/protocol/v2/turn.rs"
        val addedPaths = setOf(completeSchemaPath, threadPath, turnPath)
        val existingInputs = current.getValue("inputs").jsonArray.filterNot { input ->
            input.jsonObject.getValue("path").jsonPrimitive.content in addedPaths
        }
        put(
            "inputs",
            JsonArray(
                existingInputs + listOf(
                    provenanceInput(
                        completeSchemaPath,
                        "b7526b080da1487feb2bd5d1d5f8908b00ef1b88",
                        schema,
                    ),
                    provenanceInput(
                        threadPath,
                        "367a4a64c8f22552ecb964b9af07a4b81ab097a2",
                        thread,
                    ),
                    provenanceInput(
                        turnPath,
                        "af99b4b2e72501c50795df51481f32f11111f0a9",
                        turn,
                    ),
                ),
            ),
        )
        put(
            "completeSchemaExtraction",
            JsonPrimitive("git show rust-v0.144.6:$completeSchemaPath"),
        )
        put("generator", buildJsonObject {
            put("version", GENERATOR_VERSION)
            put(
                "command",
                "./gradlew updateProtocol -PcodexProtocolSchema=/path/to/codex_app_server_protocol.schemas.json " +
                    "-PcodexProtocolCommon=/path/to/common.rs -PcodexProtocolThread=/path/to/thread.rs " +
                    "-PcodexProtocolTurn=/path/to/turn.rs",
            )
            putJsonArray("outputs") {
                listOf(descriptor, kotlin, models).forEach { output ->
                    add(buildJsonObject {
                        put("path", output.relativeTo(file.parentFile.parentFile).invariantSeparatorsPath)
                        put("sha256", output.sha256())
                    })
                }
            }
        })
    })
    writeText(file, JSON.encodeToString(JsonObject.serializer(), updated) + "\n")
}

private fun provenanceInput(path: String, gitBlob: String, file: File) = buildJsonObject {
    put("path", path)
    put("gitBlob", gitBlob)
    put("sha256", file.sha256())
}

private fun writeText(file: File, value: String) {
    writeBytes(file, value.toByteArray())
}

private fun writeBytes(file: File, value: ByteArray) {
    file.parentFile.mkdirs()
    val temporary = File(file.parentFile, ".${file.name}.next")
    temporary.writeBytes(value)
    check(temporary.renameTo(file) || (file.delete() && temporary.renameTo(file))) {
        "Unable to replace ${file.path}"
    }
}

private fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

private fun String.kotlinTypeName(): String = split(Regex("[^A-Za-z0-9]+")).filter(String::isNotEmpty)
    .joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }
    .let { if (it.firstOrNull()?.isDigit() == true) "Method$it" else it }
