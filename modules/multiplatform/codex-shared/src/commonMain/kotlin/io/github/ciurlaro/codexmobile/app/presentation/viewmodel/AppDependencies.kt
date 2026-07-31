package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionController
import kotlinx.coroutines.flow.StateFlow

interface AppPlatform {
    fun hasStoragePermission(): Boolean
    fun configuredWorkspacePath(): String?
    fun activeWorkspacePath(): String?
    fun workspaceRoots(): List<String>
    fun workspaceDirectories(path: String?): List<String>
    fun workspaceParent(path: String): String?
    fun selectWorkspace(path: String): String
    fun eraseAppData(): Boolean
}

data class AppSessionHandle(
    val controller: CodexSessionController,
    val serviceInstanceId: String,
    val notificationsEnabled: () -> Boolean,
    val signOut: () -> Unit,
)

interface AppSessionHost {
    val failure: StateFlow<String?>
    fun attach(onConnected: (AppSessionHandle) -> Unit, onEnded: () -> Unit)
    fun startAndBind(authenticate: Boolean): Boolean
    fun bind(create: Boolean = false): Boolean
    fun unbind()
    fun wasActive(): Boolean
    fun markInactive()
}
