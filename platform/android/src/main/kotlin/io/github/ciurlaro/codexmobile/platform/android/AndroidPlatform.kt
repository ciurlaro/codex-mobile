package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.content.Intent
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
    private val providers = AndroidProviderRegistry(appContext)
    val builtInToolDispatcher: BuiltInToolDispatcher? = providers.dispatcher
    val providerPackages = AndroidProviderPackageManager(appContext, providers)
    val skillPackages = AndroidSkillPackageManager(appContext)

    fun createCodexRuntime(): CodexRuntime =
        AndroidCodexRuntime(appContext, runtimeOverride)

    fun hasStoragePermission(): Boolean = workspace.hasStoragePermission()

    fun configuredWorkspacePath(): String? =
        workspace.activeWorkspace()?.path ?: workspace.configuredPath()

    fun activeWorkspacePath(): String? = workspace.activeWorkspace()?.path

    fun workspaceRoots(): List<String> = workspace.roots().map(File::getPath)

    fun workspaceDirectories(path: String?): List<String> = workspace.directories(path).map(File::getPath)

    fun workspaceParent(path: String): String? = workspace.parent(path)?.path

    fun selectWorkspace(path: String): String = workspace.select(path).path

    fun clearWorkspace() = workspace.clear()

    fun providerSettings(): List<ProviderSettingsEntry> = providers.settings()

    fun openProviderSettings(pluginId: String) {
        val entry = providers.settings().singleOrNull { it.pluginId == pluginId }
            ?: error("Provider settings are unavailable")
        val activityClassName = checkNotNull(entry.activityClassName) { "Provider settings are unavailable" }
        appContext.startActivity(
            Intent().setClassName(appContext, activityClassName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    suspend fun finishProviderRemoval(pluginId: String) = providerPackages.remove(pluginId)
}
