package io.github.ciurlaro.codexmobile.app.composition

import android.app.ActivityManager
import android.content.Context
import io.github.ciurlaro.codexmobile.app.presentation.viewmodel.AppPlatform
import io.github.ciurlaro.codexmobile.app.runtime.bootstrap.AndroidRuntimeBootstrap
import io.github.ciurlaro.codexmobile.app.workspace.WorkspaceManager
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import java.io.File

class AndroidPlatform internal constructor(
    context: Context,
    runtimeOverride: File?,
) : AppPlatform {
    constructor(context: Context) : this(context, null)

    private val appContext = context.applicationContext
    private val workspace = WorkspaceManager(appContext)
    private val runtime = AndroidRuntimeBootstrap(context, runtimeOverride)

    fun createCodexRuntime(): CodexRuntime = runtime.create()

    override fun hasStoragePermission(): Boolean = workspace.hasStoragePermission()

    override fun configuredWorkspacePath(): String? =
        workspace.activeWorkspace()?.path ?: workspace.configuredPath()

    override fun activeWorkspacePath(): String? = workspace.activeWorkspace()?.path

    override fun workspaceRoots(): List<String> = workspace.roots().map(File::getPath)

    override fun workspaceDirectories(path: String?): List<String> =
        workspace.directories(path).map(File::getPath)

    override fun workspaceParent(path: String): String? = workspace.parent(path)?.path

    override fun selectWorkspace(path: String): String = workspace.select(path).path

    fun clearWorkspace() = workspace.clear()

    override fun eraseAppData(): Boolean =
        appContext.getSystemService(ActivityManager::class.java).clearApplicationUserData()
}
