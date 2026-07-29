package io.github.ciurlaro.codexmobile.agent

import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ThreadProviderState(
    val originalPluginIds: Set<String>,
    val lastAvailability: Map<String, Boolean>,
)

internal class ThreadProviderStateStore(private val directory: Path?) {
    private val lock = PortableLock()

    fun read(threadId: String): ThreadProviderState? = lock.withLock {
        val file = file(threadId)?.takeIf(Path::isRegularFile) ?: return@withLock null
        runCatching {
            val root = Json.parseToJsonElement(file.readUtf8()).jsonObject
            val original = root.getValue("originalPluginIds").jsonArray
                .mapTo(linkedSetOf()) { it.jsonPrimitive.content }
            val availability = root.getValue("lastAvailability").jsonObject
                .mapValues { it.value.jsonPrimitive.boolean }
            check(availability.keys == original) { "Thread provider state does not match its original schemas" }
            ThreadProviderState(original, availability)
        }.getOrNull()
    }

    fun write(threadId: String, state: ThreadProviderState) = lock.withLock {
        check(state.lastAvailability.keys == state.originalPluginIds)
        val destination = file(threadId) ?: return@withLock
        destination.writeUtf8Atomically(buildJsonObject {
            put("originalPluginIds", buildJsonArray {
                state.originalPluginIds.sorted().forEach { add(JsonPrimitive(it)) }
            })
            put("lastAvailability", buildJsonObject {
                state.lastAvailability.toSortedMap().forEach { (pluginId, enabled) -> put(pluginId, enabled) }
            })
        }.toString())
    }

    fun delete(threadId: String) = lock.withLock { file(threadId)?.deleteIfPresent() }

    private fun file(threadId: String): Path? = directory?.let { Path(it, "${threadId.sha256Hex()}.json") }
}
