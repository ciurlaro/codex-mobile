package io.github.ciurlaro.codexmobile.app

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.codex.CodexAgentClient
import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.ApprovalRequirement
import io.github.ciurlaro.codexmobile.core.ToolEffect
import io.github.ciurlaro.codexmobile.core.ToolExecutor
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform

internal class AppGraph(context: Context) {
    val platform = AndroidPlatform(context)
    private val deviceTools = platform.deviceTools()
    val toolExecutor = ToolExecutor(deviceTools, platform.mutationJournal()) { plan ->
        if (plan.effect == ToolEffect.READ) ApprovalRequirement.ALLOW else ApprovalRequirement.USER
    }

    fun newAgentClient(): AgentClient = CodexAgentClient(
        launchProcess = platform::launchProcess,
        toolDefinitions = deviceTools.map { it.definition },
    )
}
