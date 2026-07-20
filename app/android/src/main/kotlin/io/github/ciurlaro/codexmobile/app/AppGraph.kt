package io.github.ciurlaro.codexmobile.app

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.codex.CodexAgentClient
import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.ApprovalRequirement
import io.github.ciurlaro.codexmobile.core.ToolEffect
import io.github.ciurlaro.codexmobile.core.ToolExecutor
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val platform = AndroidPlatform(context)
    private val deviceTools = platform.deviceTools()
    private val mutableBackgroundFailure = MutableStateFlow<String?>(null)
    val backgroundFailure = mutableBackgroundFailure.asStateFlow()
    val toolExecutor = ToolExecutor(deviceTools, platform.mutationJournal()) { plan ->
        if (plan.effect == ToolEffect.READ) ApprovalRequirement.ALLOW else ApprovalRequirement.USER
    }

    fun newAgentClient(): AgentClient = CodexAgentClient(
        launchProcess = platform::launchProcess,
        toolDefinitions = deviceTools.map { it.definition },
    )

    fun authorizeForegroundStart(): String = UUID.randomUUID().toString().also {
        mutableBackgroundFailure.value = null
        check(backgroundPreferences().edit().putString(START_AUTHORIZATION, it).commit()) {
            "Unable to authorize background work"
        }
    }

    @Synchronized
    fun consumeForegroundStart(authorization: String?): Boolean {
        if (authorization == null || backgroundPreferences().getString(START_AUTHORIZATION, null) != authorization) {
            return false
        }
        return backgroundPreferences().edit().remove(START_AUTHORIZATION).commit()
    }

    @Synchronized
    fun revokeForegroundStart(authorization: String) {
        if (backgroundPreferences().getString(START_AUTHORIZATION, null) == authorization) {
            backgroundPreferences().edit().remove(START_AUTHORIZATION).commit()
        }
    }

    fun markBackgroundActive(active: Boolean) {
        backgroundPreferences()
            .edit()
            .putBoolean(BACKGROUND_ACTIVE, active)
            .commit()
    }

    fun wasBackgroundActive(): Boolean =
        backgroundPreferences().getBoolean(BACKGROUND_ACTIVE, false)

    fun reportBackgroundFailure(kind: String) {
        mutableBackgroundFailure.value = "Android could not start background work ($kind)"
    }

    private fun backgroundPreferences() =
        appContext.getSharedPreferences(BACKGROUND_STATE, Context.MODE_PRIVATE)

    private companion object {
        const val BACKGROUND_STATE = "background-state"
        const val BACKGROUND_ACTIVE = "active"
        const val START_AUTHORIZATION = "start-authorization"
    }
}
