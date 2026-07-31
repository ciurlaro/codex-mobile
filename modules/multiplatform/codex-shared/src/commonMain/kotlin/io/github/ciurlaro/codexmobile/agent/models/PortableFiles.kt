package io.github.ciurlaro.codexmobile.agent

import kotlin.time.Clock
import okio.FileSystem
import okio.Path
import okio.ByteString.Companion.encodeUtf8
import okio.buffer

internal fun Path.isRegularFile(): Boolean =
    FileSystem.SYSTEM.metadataOrNull(this)?.isRegularFile == true

internal fun Path.readUtf8(): String =
    FileSystem.SYSTEM.source(this).buffer().use { source ->
        source.readByteArray().decodeToString(throwOnInvalidSequence = true)
    }

internal fun Path.writeUtf8Atomically(value: String) {
    val parent = checkNotNull(parent) { "A persisted file must have a parent directory" }
    val fileSystem = FileSystem.SYSTEM
    fileSystem.createDirectories(parent)
    val next = parent / ".$name.next"
    try {
        fileSystem.sink(next).buffer().use { it.writeUtf8(value) }
        fileSystem.atomicMove(next, this)
    } catch (error: Throwable) {
        runCatching { fileSystem.delete(next) }
        throw error
    }
}

internal fun Path.deleteIfPresent() {
    FileSystem.SYSTEM.delete(this, mustExist = false)
}

internal fun String.sha256Hex(): String = encodeUtf8().sha256().hex()

internal fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

internal fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
