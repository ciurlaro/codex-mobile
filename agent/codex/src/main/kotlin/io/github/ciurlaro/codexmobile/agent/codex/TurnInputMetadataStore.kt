package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentInvocation
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class TurnInputMetadata(
    val clientMessageId: String,
    val invocations: List<AgentInvocation>,
)

internal class TurnInputMetadataStore(private val directory: File?) {
    @Synchronized
    fun read(threadId: String): Map<String, List<AgentInvocation>> {
        val file = file(threadId) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(file.readText()).jsonArray.associate { raw ->
                val item = raw.jsonObject
                item.getValue("clientMessageId").jsonPrimitive.content to
                    item.getValue("invocations").jsonArray.mapNotNull { invocation ->
                        parseInvocation(invocation.jsonObject)
                    }
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun upsert(threadId: String, metadata: TurnInputMetadata) {
        if (metadata.invocations.isEmpty()) return
        val destination = file(threadId) ?: return
        val entries = read(threadId).toMutableMap().apply {
            put(metadata.clientMessageId, metadata.invocations.distinctBy(AgentInvocation::key))
        }
        check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val next = File(destination.parentFile, ".${destination.name}.next")
        next.writeText(buildJsonArray {
            entries.forEach { (clientMessageId, invocations) ->
                add(buildJsonObject {
                    put("clientMessageId", clientMessageId)
                    put("invocations", buildJsonArray {
                        invocations.forEach { invocation ->
                            add(buildJsonObject {
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
                            })
                        }
                    })
                })
            }
        }.toString())
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

    @Synchronized
    fun delete(threadId: String) {
        file(threadId)?.delete()
    }

    private fun file(threadId: String): File? = directory?.let {
        val name = MessageDigest.getInstance("SHA-256").digest(threadId.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        File(it, "$name.json")
    }
}
