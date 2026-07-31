@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.agent.AgentClient
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentConversation
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentElicitation
import io.github.ciurlaro.codexmobile.agent.AgentElicitationAction
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentHookCatalog
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.agent.AgentPluginDetail
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackageCatalog
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.SessionId
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
    if (closed.load()) return
    when (event) {
        is AgentEvent.AuthenticationRequired -> mutableState.update {
            it.copy(
                statusMessage = "Finish sign-in in your browser",
                signInUrl = event.signInUrl,
            )
        }

        AgentEvent.Authenticated -> {
            authenticationStarted.store(false)
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
