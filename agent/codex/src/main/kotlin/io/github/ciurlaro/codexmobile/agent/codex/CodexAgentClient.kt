package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.ToolResult
import kotlinx.coroutines.flow.Flow

class CodexAgentClient(
    private val launchProcess: (command: List<String>, environment: Map<String, String>) -> Process,
) : AgentClient {
    override val events: Flow<AgentEvent>
        get() = TODO("Step 01: expose translated app-server notifications and terminal failures")

    override suspend fun authenticate() {
        TODO("Step 01: implement only the supported subscription authentication flow")
    }

    override suspend fun openSession(previous: SessionId?): SessionId =
        TODO("Step 01: start or experimentally resume one Codex session")

    override suspend fun sendPrompt(sessionId: SessionId, prompt: String) {
        TODO("Step 01: send one prompt and stream its events")
    }

    override suspend fun cancelTurn(sessionId: SessionId) {
        TODO("Step 01: cancel the active turn without killing a reusable client")
    }

    override suspend fun submitToolResult(sessionId: SessionId, result: ToolResult) {
        TODO("Step 02: translate Android's observed tool result back to app-server")
    }

    override fun close() {
        TODO("Step 01: close streams and terminate the owned process")
    }
}
