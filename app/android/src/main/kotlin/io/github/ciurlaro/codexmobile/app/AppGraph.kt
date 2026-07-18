package io.github.ciurlaro.codexmobile.app

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.codex.CodexAgentClient
import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform

internal class AppGraph(context: Context) {
    val platform = AndroidPlatform(context)

    val agentClient: AgentClient by lazy {
        CodexAgentClient(platform::launchProcess)
    }

    // TODO Step 02: compose ToolExecutor only after the tool bridge is selected.
    // TODO Step 04: compose MutationJournal only after mutation recovery starts.
}
