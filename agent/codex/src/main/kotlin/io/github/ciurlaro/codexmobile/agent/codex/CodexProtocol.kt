package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentEvent

internal object CodexProtocol {
    fun encodeRequest(id: String, method: String, paramsJson: String?): String =
        TODO("Step 01: encode the minimum app-server request set against a pinned protocol version")

    fun translateMessage(messageJson: String): List<AgentEvent> =
        TODO("Step 01: validate and translate responses/notifications without leaking provider DTOs")
}
