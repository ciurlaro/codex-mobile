package io.github.ciurlaro.codexmobile.app.composition

import android.content.Context
import io.github.ciurlaro.codexmobile.app.session.background.BackgroundSessionStore
import io.github.ciurlaro.codexmobile.agent.codex.CodexAgentClient
import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform

internal class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val clientVersion = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
    val platform = AndroidPlatform(context)
    val backgroundSessions = BackgroundSessionStore(appContext)

    fun newAgentClient(): AgentClient = CodexAgentClient(
        runtimeFactory = platform::createCodexRuntime,
        clientVersion = clientVersion,
        pluginCacheDirectory = java.io.File(appContext.cacheDir, "plugin-catalogs"),
        threadProviderStateDirectory = java.io.File(appContext.noBackupFilesDir, "thread-providers"),
        shellTranscriptDirectory = java.io.File(appContext.noBackupFilesDir, "shell-transcripts"),
        turnInputMetadataDirectory = java.io.File(appContext.noBackupFilesDir, "turn-inputs"),
        builtInToolDispatcher = platform.builtInToolDispatcher,
        providerHost = platform.providerPackages,
    )
}
