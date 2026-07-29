package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlinx.coroutines.delay
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun consumeProcessOutput(
    outputFile: Path,
    processIsAlive: () -> Boolean,
    maxBytes: Int = MAX_RECEIVED_MESSAGE_BYTES,
    onLine: (String) -> Unit,
) {
    val framer = StrictJsonLineFramer(maxBytes)
    val buffer = ByteArray(8 * 1024)
    val input = SystemFileSystem.source(outputFile).buffered()
    try {
        while (true) {
            val count = input.readAtMostTo(buffer)
            if (count > 0) {
                framer.accept(buffer.copyOf(count), onLine)
            } else if (processIsAlive()) {
                delay(OUTPUT_POLL_INTERVAL)
            } else {
                framer.finish(onLine)
                return
            }
        }
    } finally {
        input.close()
        if (SystemFileSystem.exists(outputFile)) SystemFileSystem.delete(outputFile)
    }
}

private val OUTPUT_POLL_INTERVAL = 10.milliseconds
