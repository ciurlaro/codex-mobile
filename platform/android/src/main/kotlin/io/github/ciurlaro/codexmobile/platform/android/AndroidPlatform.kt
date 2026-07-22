package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.codex.CodexRuntime
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolDispatcher
import java.io.File

class AndroidPlatform internal constructor(
    context: Context,
    private val runtimeOverride: File?,
) {
    constructor(context: Context) : this(context, null)

    private val appContext = context.applicationContext
    private val workspace = WorkspaceManager(appContext)
    private val privateBackends = PrivateBackendBundle(appContext)
    private val builtInPlugins = BuiltInPluginBundle(appContext)
    val builtInToolDispatcher: BuiltInToolDispatcher =
        AndroidBuiltInToolDispatcher(appContext, privateBackends, workspace)
    val skillPackages = AndroidSkillPackageManager(appContext)
    private val telegram = TelegramIntegration(privateBackends)

    fun createCodexRuntime(): CodexRuntime =
        AndroidCodexRuntime(appContext, builtInPlugins, runtimeOverride)

    fun hasStoragePermission(): Boolean = workspace.hasStoragePermission()

    fun configuredWorkspacePath(): String? =
        workspace.activeWorkspace()?.path ?: workspace.configuredPath()

    fun activeWorkspacePath(): String? = workspace.activeWorkspace()?.path

    fun workspaceRoots(): List<String> = workspace.roots().map(File::getPath)

    fun workspaceDirectories(path: String?): List<String> = workspace.directories(path).map(File::getPath)

    fun workspaceParent(path: String): String? = workspace.parent(path)?.path

    fun selectWorkspace(path: String): String = workspace.select(path).path

    fun clearWorkspace() = workspace.clear()

    fun telegramAvailable(): Boolean = telegram.available

    fun telegramStatus(): TelegramStatus = telegram.status()

    fun startTelegramAuthentication(phoneNumber: String): TelegramAuthSession =
        telegram.startAuthentication(phoneNumber)

    fun disconnectTelegram(): TelegramDisconnectResult = telegram.disconnect()
}
