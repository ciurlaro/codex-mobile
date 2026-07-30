package io.github.ciurlaro.codexmobile.appserver.runtime

import io.matthewnelson.kmp.process.OutputFeed

internal class RuntimeProcessOutputFeed(
    private val maxBytes: Int = MAX_RECEIVED_MESSAGE_BYTES,
    private val onLine: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onEnd: () -> Unit = {},
) : OutputFeed {
    private var failed = false

    init {
        require(maxBytes > 0)
    }

    override fun onOutput(line: String?) {
        if (line == null) {
            onEnd()
            return
        }
        if (failed || line.isEmpty()) return
        try {
            // ponytail: kmp-process 0.5.0 exposes decoded whole lines only. Enforce the
            // limit and reject decoder replacements until it offers bounded raw bytes.
            check('\uFFFD' !in line) { "JSON-RPC frame is not valid UTF-8" }
            check(line.encodeToByteArray().size <= maxBytes) {
                "JSON-RPC frame exceeds $maxBytes bytes"
            }
            onLine(line)
        } catch (error: Throwable) {
            failed = true
            onFailure(error)
        }
    }
}
