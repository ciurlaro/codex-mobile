package io.github.ciurlaro.codexmobile.app.composition

import android.content.Context
import io.github.ciurlaro.codexmobile.app.persistence.AppPreferencesStore
import io.github.ciurlaro.codexmobile.app.presentation.viewmodel.AppViewModel
import io.github.ciurlaro.codexmobile.app.session.background.BackgroundSessionStore
import io.github.ciurlaro.codexmobile.app.session.background.AndroidSessionHost
import io.github.ciurlaro.codexmobile.agent.CodexAgentClient
import io.github.ciurlaro.codexmobile.agent.AgentClient
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path.Companion.toPath

internal class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val clientVersion = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
    val platform = AndroidPlatform(context)
    val backgroundSessions = BackgroundSessionStore(appContext)
    val preferences = AppPreferencesStore(
        "${appContext.filesDir.absolutePath}/datastore/chat-ui.preferences_pb".toPath(),
    )

    fun newViewModel(): AppViewModel =
        AppViewModel(platform, preferences, AndroidSessionHost(appContext, backgroundSessions))

    fun newAgentClient(): AgentClient = CodexAgentClient(
        runtimeFactory = platform::createCodexRuntime,
        clientVersion = clientVersion,
        pluginCacheDirectory = "${appContext.cacheDir.absolutePath}/plugin-catalogs".toPath(),
        shellTranscriptDirectory = "${appContext.noBackupFilesDir.absolutePath}/shell-transcripts".toPath(),
        turnInputMetadataDirectory = "${appContext.noBackupFilesDir.absolutePath}/turn-inputs".toPath(),
        coroutineDispatcher = Dispatchers.IO,
        fileSystem = FileSystem.SYSTEM,
    )
}
