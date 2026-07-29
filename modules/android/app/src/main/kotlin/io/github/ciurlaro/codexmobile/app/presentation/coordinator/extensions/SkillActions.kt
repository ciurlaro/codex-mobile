package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ciurlaro.codexmobile.app.lifecycle.CodexMobileApplication
import io.github.ciurlaro.codexmobile.app.persistence.AppPreferencesStore
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.planCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.withoutActiveInvocationToken
import io.github.ciurlaro.codexmobile.app.presentation.invocation.withRecentInvocation
import io.github.ciurlaro.codexmobile.app.presentation.mapper.toChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionActionError
import io.github.ciurlaro.codexmobile.app.presentation.model.CustomExtensionSource
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.afterExpiry
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionRemoval
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionType
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_URL
import io.github.ciurlaro.codexmobile.app.presentation.model.OPENAI_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.canonicalPluginSourceId
import io.github.ciurlaro.codexmobile.app.presentation.model.enabledMarketplaceNames
import io.github.ciurlaro.codexmobile.app.presentation.model.initialExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.reconcilePendingPluginSetups
import io.github.ciurlaro.codexmobile.app.presentation.state.withNewChat
import io.github.ciurlaro.codexmobile.app.presentation.state.withStreamingAssistant
import io.github.ciurlaro.codexmobile.app.presentation.state.withSubmittedTurn
import io.github.ciurlaro.codexmobile.app.presentation.state.withoutConversation
import io.github.ciurlaro.codexmobile.app.presentation.state.connectorsNeedingOnUseAuthentication
import io.github.ciurlaro.codexmobile.app.presentation.state.selectedModelOrNull
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionController
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionState
import io.github.ciurlaro.codexmobile.app.session.background.CodexForegroundService
import io.github.ciurlaro.codexmobile.app.session.background.CodexServiceConnection
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.PLAN_CLIENT_MESSAGE_PREFIX
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginSummary
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.SessionId
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull


internal fun AppViewModel.installSkillAction(packageInfo: AgentSkillPackage) = extensionMutation(
    "skill:${packageInfo.id}",
    "Skill could not be installed",
) {
    serviceController?.installSkill(packageInfo)
    mutableState.update {
        it.copy(
            availableSkills = it.availableSkills.filterNot { candidate -> candidate.id == packageInfo.id },
        )
    }
    loadSkills(forceReload = true)
}

internal fun AppViewModel.requestUninstallSkillAction(skill: AgentSkill) {
    if (skill.canUninstall) mutableState.update {
        it.copy(pendingExtensionRemoval = ExtensionRemoval.Skill(skill))
    }
}

internal fun AppViewModel.loadCurrentExtensionsAction(forceReload: Boolean) {
    val current = mutableState.value
    when (current.extensionType) {
        ExtensionType.SKILLS -> when (current.extensionStatus) {
            ExtensionStatus.INSTALLED -> loadSkills(forceReload)
            ExtensionStatus.UNINSTALLED -> loadAvailableSkills(forceReload)
            ExtensionStatus.SETUP_PENDING, ExtensionStatus.UNAVAILABLE -> Unit
        }
        ExtensionType.PLUGINS -> loadPluginCatalog(forceReload)
    }
}

internal fun AppViewModel.loadSkillsAction(forceReload: Boolean) {
    val controller = serviceController ?: return
    val current = mutableState.value
    if (!forceReload && (current.skillsLoaded || skillsJob?.isActive == true)) return
    if (forceReload) skillsJob?.cancel()
    val workingDirectory = container.platform.activeWorkspacePath() ?: return
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

internal fun AppViewModel.loadAvailableSkillsAction(forceReload: Boolean) {
    val selectedSources = mutableState.value
    val openAiEnabled = OPENAI_PLUGIN_SOURCE_ID in selectedSources.enabledExtensionSourceIds
    val customSources = selectedSources.customExtensionSources.filter {
        it.supportsSkills && it.id in selectedSources.enabledExtensionSourceIds
    }
    if (!openAiEnabled && customSources.isEmpty()) {
        availableSkillsJob?.cancel()
        availableSkillsJob = null
        mutableState.update {
            it.copy(
                availableSkills = emptyList(),
                availableSkillsLoaded = true,
                isAvailableSkillsLoading = false,
                availableSkillsError = null,
            )
        }
        return
    }
    val controller = serviceController ?: return
    val current = mutableState.value
    if (!forceReload && (current.availableSkillsLoaded || availableSkillsJob?.isActive == true)) return
    if (forceReload) availableSkillsJob?.cancel()
    mutableState.update { it.copy(isAvailableSkillsLoading = true, availableSkillsError = null) }
    availableSkillsJob = scope.launch {
        val installed = mutableState.value.skills.map(AgentSkill::name).toSet()
        val (openAiResult, customResults) = coroutineScope {
            val openAi = async {
                if (openAiEnabled) runCatching {
                    controller.listAvailableSkills(installed, forceReload)
                } else null
            }
            val custom = customSources.map { source ->
                async { source to runCatching { controller.discoverGitHubSkills(source.url) } }
            }
            openAi.await() to custom.map { it.await() }
        }
        ensureActive()
        (openAiResult?.exceptionOrNull() as? CancellationException)?.let { throw it }
        customResults.forEach { (_, result) ->
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        }
        if (serviceController !== controller) return@launch
        val errors = buildList {
            openAiResult?.exceptionOrNull()?.message?.let(::add)
            openAiResult?.getOrNull()?.errors?.let(::addAll)
            customResults.forEach { (source, result) ->
                result.exceptionOrNull()?.message?.let { add("${source.url}: $it") }
            }
        }
        val packages = buildList {
            openAiResult?.getOrNull()?.skills?.let(::addAll)
            customResults.forEach { (_, result) -> result.getOrNull()?.let(::addAll) }
        }.filterNot { it.name in installed }.distinctBy(AgentSkillPackage::name)
        val refreshAfterCache = !forceReload &&
            openAiResult?.getOrNull()?.freshness == AgentCatalogFreshness.STALE_CACHE
        mutableState.update {
            it.copy(
                availableSkills = packages,
                availableSkillsLoaded = true,
                isAvailableSkillsLoading = refreshAfterCache,
                availableSkillsError = errors.distinct().joinToString("\n").ifBlank { null },
            )
        }
        if (refreshAfterCache) {
            availableSkillsJob = null
            loadAvailableSkills(forceReload = true)
        }
    }
}
