package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun AppViewModel.loadCurrentExtensionsAction(forceReload: Boolean) {
    when (mutableState.value.extensionType) {
        ExtensionType.SKILLS -> loadSkills(forceReload)
        ExtensionType.PLUGINS -> loadPluginCatalog(forceReload)
    }
}

internal fun AppViewModel.loadSkillsAction(forceReload: Boolean) {
    val controller = serviceController ?: return
    val current = mutableState.value
    if (!forceReload && (current.skillsLoaded || skillsJob?.isActive == true)) return
    if (forceReload) skillsJob?.cancel()
    val workingDirectory = platform.activeWorkspacePath() ?: return
    mutableState.update { it.copy(isSkillsLoading = true, skillsError = null) }
    skillsJob = scope.launch {
        runCatching { controller.listSkills(workingDirectory, forceReload) }
            .onSuccess { catalog ->
                if (serviceController !== controller) return@onSuccess
                mutableState.update {
                    it.copy(
                        skills = catalog.skills,
                        skillsLoaded = true,
                        isSkillsLoading = false,
                        skillsError = catalog.errors.distinct().joinToString("\n").ifBlank { null },
                    )
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (serviceController !== controller) return@onFailure
                mutableState.update {
                    it.copy(
                        skillsLoaded = true,
                        isSkillsLoading = false,
                        skillsError = error.message?.take(300) ?: "Skills could not be loaded",
                    )
                }
            }
    }
}
