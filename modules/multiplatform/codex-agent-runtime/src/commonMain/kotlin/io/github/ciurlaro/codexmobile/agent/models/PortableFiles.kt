package io.github.ciurlaro.codexmobile.agent

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.time.Clock

internal fun Path.isRegularFile(): Boolean = SystemFileSystem.metadataOrNull(this)?.isRegularFile == true

internal fun Path.readUtf8(): String {
    val input = SystemFileSystem.source(this).buffered()
    return try {
        input.readByteArray().decodeToString(throwOnInvalidSequence = true)
    } finally {
        input.close()
    }
}

internal fun Path.writeUtf8Atomically(value: String) {
    val parent = checkNotNull(parent) { "A persisted file must have a parent directory" }
    SystemFileSystem.createDirectories(parent)
    val next = Path(parent, ".$name.next")
    val output = SystemFileSystem.sink(next).buffered()
    try {
        output.write(value.encodeToByteArray())
    } finally {
        output.close()
    }
    SystemFileSystem.atomicMove(next, this)
}

internal fun Path.deleteIfPresent() {
    if (SystemFileSystem.exists(this)) SystemFileSystem.delete(this)
}

internal fun String.sha256Hex(): String = SHA256().digest(encodeToByteArray()).toHex()

internal fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

internal fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
