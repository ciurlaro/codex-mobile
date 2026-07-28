package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.core.AgentClient
import io.github.ciurlaro.codexmobile.core.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.core.AgentConversation
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentElicitation
import io.github.ciurlaro.codexmobile.core.AgentElicitationAction
import io.github.ciurlaro.codexmobile.core.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentMcpServer
import io.github.ciurlaro.codexmobile.core.AgentHookCatalog
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.core.AgentPluginDetail
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentSkillPackageCatalog
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.platform.android.AndroidSkillPackageManager
import io.github.ciurlaro.codexmobile.platform.android.AndroidPluginMarketplaceManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull


internal fun CodexSessionController.reduceAction(event: AgentEvent) {
    if (closed.get()) return
    when (event) {
        is AgentEvent.AuthenticationRequired -> mutableState.update {
            it.copy(
                statusMessage = "Finish sign-in in your browser",
                signInUrl = event.signInUrl,
            )
        }

        AgentEvent.Authenticated -> {
            synchronized(lock) { authenticationStarted = false }
            mutableState.update {
                it.copy(
                    statusMessage = "Ready",
                    isAuthenticated = true,
                    signInUrl = null,
                    diagnosticCode = null,
                )
            }
        }

        is AgentEvent.SessionOpened -> mutableState.update {
            it.copy(
                statusMessage = "Ready",
                sessionId = event.sessionId,
                isAuthenticated = true,
                activeModel = event.model ?: it.activeModel,
                activeEffort = event.effort ?: it.activeEffort,
                activeServiceTier = event.serviceTier ?: it.activeServiceTier,
                diagnosticCode = null,
            )
        }

        is AgentEvent.TextDelta -> if (event.isCommentary) {
            appendThoughtText(event.sessionId, event.text, event.itemId ?: "commentary", null)
        } else {
            appendStreamedText(event.sessionId, event.text)
        }

        is AgentEvent.ReasoningSummaryDelta -> appendReasoningSummary(event)

        is AgentEvent.PlanDelta -> appendPlan(event)

        is AgentEvent.PlanUpdated -> mutableState.update {
            if (it.sessionId == event.sessionId) it.copy(planProgress = event.progress) else it
        }

        is AgentEvent.HookActivityChanged -> mutableState.update { current ->
            if (current.sessionId != event.sessionId) current else current.copy(
                hookActivities = (current.hookActivities.filterNot { it.id == event.activity.id } +
                    event.activity).takeLast(MAX_HOOK_ACTIVITIES),
            )
        }

        is AgentEvent.ShellOutputDelta -> appendStreamedText(event.sessionId, event.text)

        is AgentEvent.ShellCommandCompleted -> mutableState.update {
            if (it.sessionId == event.sessionId) it.copy(shellExitCode = event.exitCode) else it
        }

        is AgentEvent.TurnCompleted -> {
            resetTurnState()
            mutableState.update {
                if (it.sessionId == event.sessionId) {
                    it.copy(statusMessage = "Ready", isTurnActive = false, diagnosticCode = null)
                } else {
                    it
                }
            }
        }

        is AgentEvent.Failure -> {
            resetTurnState()
            resetAuthenticationState()
            mutableState.update {
                it.copy(
                    statusMessage = event.message.take(MAX_VISIBLE_ERROR_CHARS),
                    sessionId = if (event.sessionId == null) null else it.sessionId,
                    signInUrl = null,
                    isTurnActive = false,
                    pendingApproval = null,
                    pendingElicitation = null,
                    workActivity = null,
                    attentionRequired = true,
                    diagnosticCode = event.code,
                )
            }
        }

        is AgentEvent.ApprovalRequested -> {
            if (mutableState.value.pendingApproval == null) {
                mutableState.update {
                    it.copy(
                        statusMessage = "Approval needed",
                        pendingApproval = event,
                        attentionRequired = true,
                    )
                }
            } else {
                launchVisibleFailure {
                    agentClient.resolveApproval(event.requestId, AgentApprovalDecision.DECLINE)
                }
            }
        }

        is AgentEvent.WorkActivityChanged -> mutableState.update {
            if (it.sessionId == event.sessionId) it.copy(workActivity = event.activity) else it
        }

        AgentEvent.SkillsChanged -> mutableState.update {
            it.copy(skillsRevision = it.skillsRevision + 1)
        }

        AgentEvent.PluginsChanged -> mutableState.update {
            it.copy(pluginsRevision = it.pluginsRevision + 1)
        }

        AgentEvent.ConnectorsChanged -> mutableState.update {
            it.copy(connectorsRevision = it.connectorsRevision + 1)
        }

        is AgentEvent.McpOauthCompleted -> mutableState.update {
            it.copy(oauthCompletion = event, externalOperation = null)
        }

        is AgentEvent.ElicitationRequested -> mutableState.update {
            if (it.pendingElicitation == null) {
                it.copy(
                    statusMessage = "Information needed",
                    pendingElicitation = event.elicitation,
                    attentionRequired = true,
                )
            } else {
                launchVisibleFailure {
                    agentClient.resolveElicitation(
                        event.elicitation.requestId,
                        AgentElicitationResponse(AgentElicitationAction.DECLINE),
                    )
                }
                it
            }
        }
    }
}
