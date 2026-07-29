package io.github.ciurlaro.codexmobile.app.composition

import android.content.Context
import io.github.ciurlaro.codexmobile.app.session.background.BackgroundSessionStore
import io.github.ciurlaro.codexmobile.agent.CodexAgentClient
import io.github.ciurlaro.codexmobile.agent.AgentClient
import kotlinx.io.files.Path

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
        pluginCacheDirectory = Path(appContext.cacheDir.absolutePath, "plugin-catalogs"),
        threadProviderStateDirectory = Path(appContext.noBackupFilesDir.absolutePath, "thread-providers"),
        shellTranscriptDirectory = Path(appContext.noBackupFilesDir.absolutePath, "shell-transcripts"),
        turnInputMetadataDirectory = Path(appContext.noBackupFilesDir.absolutePath, "turn-inputs"),
        builtInToolDispatcher = platform.builtInToolDispatcher,
        providerHost = platform.providerPackages,
    )
}
