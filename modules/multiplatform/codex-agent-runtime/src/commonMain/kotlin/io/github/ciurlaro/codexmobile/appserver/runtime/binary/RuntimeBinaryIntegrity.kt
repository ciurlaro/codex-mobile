package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.kotlincrypto.hash.sha2.SHA256

internal fun Path.isRegularFile(): Boolean =
    SystemFileSystem.metadataOrNull(this)?.isRegularFile == true

internal fun Path.sha256(): String {
    val digest = SHA256()
    val buffer = ByteArray(16 * 1024)
    val input = SystemFileSystem.source(this).buffered()
    try {
        while (true) {
            val count = input.readAtMostTo(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    } finally {
        input.close()
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
