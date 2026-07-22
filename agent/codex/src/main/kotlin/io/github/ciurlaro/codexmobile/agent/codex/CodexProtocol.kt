package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentMessage
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.deriveConversationTitle
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun compactDescription(value: JsonElement): String = value.toString().let {
    if (it.length <= 2_000) it else it.take(2_000) + "…"
}

internal fun conversationSummary(thread: JsonObject): AgentConversationSummary {
    val preview = cleanTaggedPreview(thread.requiredText("preview"))
    return AgentConversationSummary(
        sessionId = SessionId(thread.requiredString("id")),
        title = deriveConversationTitle(thread.optionalString("name"), preview),
        updatedAtEpochSeconds = thread.requiredLong("updatedAt"),
    )
}

internal fun conversationMessage(rawItem: JsonElement): AgentMessage? {
    val item = rawItem.jsonObject
    return when (item.requiredString("type")) {
        "userMessage" -> {
            val content = item.requiredArray("content").map(JsonElement::jsonObject)
            val invocations = content.mapNotNull(::parseInvocation).distinctBy(AgentInvocation::key)
            val prompts = content.mapNotNull { input ->
                input.takeIf { it.optionalString("type") == "text" }
                    ?.let { parsePrompt(it, invocations) }
            }
            if (prompts.isEmpty() && invocations.isEmpty()) return null
            AgentMessage(
                id = item.requiredString("id"),
                clientId = item.optionalString("clientId"),
                role = AgentMessageRole.USER,
                text = prompts.joinToString("\n", transform = ParsedPrompt::text),
                capabilities = prompts.flatMap(ParsedPrompt::capabilities).toSet(),
                invocations = invocations,
            )
        }

        "agentMessage" -> AgentMessage(
            id = item.requiredString("id"),
            clientId = null,
            role = AgentMessageRole.CODEX,
            text = item.requiredText("text"),
        )

        else -> null
    }
}

internal fun turnInput(request: AgentTurnRequest): JsonArray {
    val capabilities = request.capabilities.sortedBy(AgentCapability::id)
    val invocations = request.invocations.distinctBy(AgentInvocation::key)
    val tagBlock = buildList {
        addAll(capabilities.map(AgentCapability::promptLabel))
        addAll(invocations.map {
            when (it) {
                is AgentInvocation.Skill -> "\$${it.name}"
                is AgentInvocation.Plugin -> "@${it.name}"
            }
        })
    }.joinToString("\n")
    val text = when {
        tagBlock.isEmpty() -> request.prompt
        request.prompt.isBlank() -> tagBlock
        else -> "$tagBlock\n\n${request.prompt}"
    }
    return buildJsonArray {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
                if (capabilities.isNotEmpty()) {
                    put(
                        "text_elements",
                        buildJsonArray {
                            var start = 0
                            capabilities.forEach { capability ->
                                val end = start + capability.promptLabel
                                    .toByteArray(StandardCharsets.UTF_8)
                                    .size
                                add(
                                    buildJsonObject {
                                        putJsonObject("byteRange") {
                                            put("start", start)
                                            put("end", end)
                                        }
                                        put("placeholder", capability.displayLabel)
                                    },
                                )
                                start = end + 1
                            }
                        },
                    )
                }
            },
        )
        invocations.forEach { add(invocationInput(it)) }
    }
}

internal fun parsePrompt(
    input: JsonObject,
    invocations: List<AgentInvocation> = emptyList(),
): ParsedPrompt {
    val text = input.requiredText("text")
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    val capabilities = input.optionalArray("text_elements").mapNotNull { rawElement ->
        runCatching {
            val element = rawElement.jsonObject
            val capability = AgentCapability.entries.singleOrNull {
                it.displayLabel == element.optionalString("placeholder")
            } ?: return@runCatching null
            val range = element.requiredObject("byteRange")
            val start = range.requiredLong("start").toInt()
            val end = range.requiredLong("end").toInt()
            capability.takeIf {
                start >= 0 && end in start..bytes.size &&
                    bytes.copyOfRange(start, end).toString(StandardCharsets.UTF_8) == it.promptLabel
            }
        }.getOrNull()
    }.toSet()
    val tagBlock = buildList {
        addAll(capabilities.sortedBy(AgentCapability::id).map(AgentCapability::promptLabel))
        addAll(invocations.map {
            when (it) {
                is AgentInvocation.Skill -> "\$${it.name}"
                is AgentInvocation.Plugin -> "@${it.name}"
            }
        })
    }.joinToString("\n")
    val visibleText = when {
        tagBlock.isEmpty() -> text
        text == tagBlock -> ""
        text.startsWith("$tagBlock\n\n") -> text.removePrefix("$tagBlock\n\n")
        else -> text
    }
    return ParsedPrompt(visibleText, capabilities)
}

internal fun cleanTaggedPreview(preview: String): String {
    val labels = AgentCapability.entries.map(AgentCapability::promptLabel).toSet()
    val lines = preview.lines()
    val firstVisible = lines.indexOfFirst { it !in labels && it.isNotEmpty() }
    if (firstVisible <= 0 || lines.take(firstVisible).none { it in labels }) return preview
    return lines.drop(firstVisible).joinToString("\n")
}

internal fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
        ?: error("Missing $name")

internal fun JsonObject.requiredText(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: error("Missing $name")

internal fun JsonObject.optionalString(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.requiredLong(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: error("Missing $name")

internal fun JsonObject.requiredBoolean(name: String): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: error("Missing $name")

internal fun JsonObject.requiredArray(name: String): JsonArray =
    this[name]?.jsonArray ?: error("Missing $name")

internal fun JsonObject.optionalArray(name: String): JsonArray =
    this[name]?.let { if (it is JsonNull) null else it.jsonArray } ?: JsonArray(emptyList())

internal fun JsonObject.requiredObject(name: String): JsonObject =
    this[name] as? JsonObject ?: error("Missing $name")

internal data class ParsedPrompt(
    val text: String,
    val capabilities: Set<AgentCapability>,
)
