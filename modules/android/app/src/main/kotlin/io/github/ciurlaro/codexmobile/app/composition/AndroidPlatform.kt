package io.github.ciurlaro.codexmobile.app.composition

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.BuiltInToolDispatcher
import io.github.ciurlaro.codexmobile.app.runtime.bootstrap.AndroidRuntimeBootstrap
import io.github.ciurlaro.codexmobile.app.workspace.WorkspaceManager
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.extension.host.AndroidExtensionHost
import io.github.ciurlaro.codexmobile.extension.host.ProviderSettingsEntry
import java.io.File

class AndroidPlatform internal constructor(
    context: Context,
    runtimeOverride: File?,
) {
    constructor(context: Context) : this(context, null)

    private val workspace = WorkspaceManager(context)
    private val extensions = AndroidExtensionHost(context) { root, path, mustExist ->
        workspace.resolveFile(root, path, mustExist).absolutePath
    }
    private val runtime = AndroidRuntimeBootstrap(context, runtimeOverride)

    val builtInToolDispatcher: BuiltInToolDispatcher = extensions.builtInToolDispatcher
    val providerPackages = extensions.providerPackages
    val pluginMarketplaces = extensions.pluginMarketplaces
    val skillPackages = extensions.skillPackages

    fun createCodexRuntime(): CodexRuntime = runtime.create()

    fun hasStoragePermission(): Boolean = workspace.hasStoragePermission()

    fun configuredWorkspacePath(): String? =
        workspace.activeWorkspace()?.path ?: workspace.configuredPath()

    fun activeWorkspacePath(): String? = workspace.activeWorkspace()?.path

    fun workspaceRoots(): List<String> = workspace.roots().map(File::getPath)

    fun workspaceDirectories(path: String?): List<String> = workspace.directories(path).map(File::getPath)

    fun workspaceParent(path: String): String? = workspace.parent(path)?.path

    fun selectWorkspace(path: String): String = workspace.select(path).path

    fun clearWorkspace() = workspace.clear()

    fun providerSettings(): List<ProviderSettingsEntry> = extensions.providerSettings()

    fun openProviderSettings(pluginId: String) = extensions.openProviderSettings(pluginId)

    suspend fun finishProviderRemoval(pluginId: String) = extensions.finishProviderRemoval(pluginId)
}
