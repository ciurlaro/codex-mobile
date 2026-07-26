package io.github.ciurlaro.codexmobile.agent.codex

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class ShellTranscript(
    val turnId: String,
    val itemId: String,
    val command: String,
    val output: String,
    val exitCode: Int?,
)

internal class ShellTranscriptStore(private val directory: File?) {
    @Synchronized
    fun read(threadId: String): List<ShellTranscript> {
        val file = file(threadId) ?: return emptyList()
        if (!file.isFile) return emptyList()
        return runCatching {
            Json.parseToJsonElement(file.readText()).jsonArray.map { raw ->
                val item = raw.jsonObject
                ShellTranscript(
                    turnId = item.getValue("turnId").jsonPrimitive.content,
                    itemId = item.getValue("itemId").jsonPrimitive.content,
                    command = item.getValue("command").jsonPrimitive.content,
                    output = item.getValue("output").jsonPrimitive.content,
                    exitCode = item["exitCode"]?.jsonPrimitive?.longOrNull?.toInt(),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun upsert(threadId: String, transcript: ShellTranscript) {
        val destination = file(threadId) ?: return
        val transcripts = read(threadId).filterNot { it.itemId == transcript.itemId } + transcript
        check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val next = File(destination.parentFile, ".${destination.name}.next")
        next.writeText(buildJsonArray {
            transcripts.forEach { item ->
                add(buildJsonObject {
                    put("turnId", item.turnId)
                    put("itemId", item.itemId)
                    put("command", item.command)
                    put("output", item.output)
                    put("exitCode", item.exitCode?.let(::JsonPrimitive) ?: JsonNull)
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
