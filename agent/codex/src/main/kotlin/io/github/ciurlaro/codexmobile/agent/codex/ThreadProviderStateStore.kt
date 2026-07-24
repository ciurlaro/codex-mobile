package io.github.ciurlaro.codexmobile.agent.codex

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ThreadProviderState(
    val originalPluginIds: Set<String>,
    val lastAvailability: Map<String, Boolean>,
)

internal class ThreadProviderStateStore(private val directory: File?) {
    fun read(threadId: String): ThreadProviderState? {
        val file = file(threadId) ?: return null
        if (!file.isFile) return null
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val original = root.getValue("originalPluginIds").jsonArray
                .mapTo(linkedSetOf()) { it.jsonPrimitive.content }
            val availability = root.getValue("lastAvailability").jsonObject
                .mapValues { it.value.jsonPrimitive.boolean }
            check(availability.keys == original) { "Thread provider state does not match its original schemas" }
            ThreadProviderState(original, availability)
        }.getOrNull()
    }

    fun write(threadId: String, state: ThreadProviderState) {
        val destination = file(threadId) ?: return
        check(state.lastAvailability.keys == state.originalPluginIds)
        check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val next = File(destination.parentFile, ".${destination.name}.next")
        next.writeText(
            buildJsonObject {
                put("originalPluginIds", buildJsonArray {
                    state.originalPluginIds.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
                put("lastAvailability", buildJsonObject {
                    state.lastAvailability.toSortedMap().forEach { (pluginId, enabled) -> put(pluginId, enabled) }
                })
            }.toString(),
        )
        try {
            Files.move(
                next.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(next.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(threadId: String) {
        file(threadId)?.delete()
    }

    private fun file(threadId: String): File? = directory?.let {
        val name = MessageDigest.getInstance("SHA-256").digest(threadId.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        File(it, "$name.json")
    }
}
