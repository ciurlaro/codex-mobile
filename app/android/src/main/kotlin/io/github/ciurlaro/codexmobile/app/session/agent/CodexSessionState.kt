package io.github.ciurlaro.codexmobile.app.session.agent

import io.github.ciurlaro.codexmobile.core.AgentElicitation
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.AgentWorkActivity
import io.github.ciurlaro.codexmobile.core.AgentHookActivity
import io.github.ciurlaro.codexmobile.core.AgentPlanProgress
import io.github.ciurlaro.codexmobile.core.SessionId

internal data class CodexSessionState(
    val statusMessage: String = "Starting background session…",
    val streamedText: String = "",
    val streamedReasoning: String = "",
    val streamedPlan: String = "",
    val planItemId: String? = null,
    val planProgress: AgentPlanProgress? = null,
    val hookActivities: List<AgentHookActivity> = emptyList(),
    val reasoningItemId: String? = null,
    val reasoningSummaryIndex: Long? = null,
    val shellExitCode: Int? = null,
    val sessionId: SessionId? = null,
    val isAuthenticated: Boolean = false,
    val activeModel: String? = null,
    val activeEffort: String? = null,
    val activeServiceTier: String? = null,
    val signInUrl: String? = null,
    val isTurnActive: Boolean = false,
    val pendingApproval: AgentEvent.ApprovalRequested? = null,
    val pendingElicitation: AgentElicitation? = null,
    val skillsRevision: Int = 0,
    val pluginsRevision: Int = 0,
    val connectorsRevision: Int = 0,
    val oauthCompletion: AgentEvent.McpOauthCompleted? = null,
    val externalOperation: String? = null,
    val workActivity: AgentWorkActivity? = null,
    val attentionRequired: Boolean = false,
    val diagnosticCode: String? = null,
    val terminal: Boolean = false,
)
