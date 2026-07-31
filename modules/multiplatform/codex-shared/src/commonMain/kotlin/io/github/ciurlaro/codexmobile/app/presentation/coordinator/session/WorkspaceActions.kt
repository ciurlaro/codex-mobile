package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import kotlinx.coroutines.flow.update

internal fun AppViewModel.workspaceRootsAction(): List<String> =
    runCatching { platform.workspaceRoots() }.getOrDefault(emptyList())

internal fun AppViewModel.workspaceDirectoriesAction(path: String?): List<String> =
    runCatching { platform.workspaceDirectories(path) }.getOrDefault(emptyList())

internal fun AppViewModel.workspaceParentAction(path: String): String? =
    runCatching { platform.workspaceParent(path) }.getOrNull()

internal fun AppViewModel.selectWorkspaceAction(path: String) {
    runCatching { platform.selectWorkspace(path) }
        .onSuccess { selected ->
            mutableState.update {
                it.copy(
                    statusMessage = "Workspace selected",
                    workspacePath = selected,
                    hasStorageAccess = true,
                    skills = emptyList(),
                    installedPlugins = emptyList(),
                    availablePlugins = emptyList(),
                    unavailablePluginIds = emptySet(),
                    extensionActionError = null,
                    skillsLoaded = false,
                    pluginCatalogStatus = PluginCatalogStatus.NOT_LOADED,
                    pluginCatalogError = null,
                )
            }
            loadPluginCatalog(forceReload = true)
        }
        .onFailure { mutableState.update { state -> state.copy(statusMessage = "Workspace selection failed") } }
}

internal fun AppViewModel.refreshStorageAction() {
    mutableState.update {
        it.copy(
            hasStorageAccess = platform.hasStoragePermission(),
            workspacePath = platform.configuredWorkspacePath(),
            isBackgroundNotificationVisible = serviceController?.let {
                notificationsEnabled?.invoke() ?: false
            } ?: it.isBackgroundNotificationVisible,
        )
    }
}
