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


internal fun CodexSessionController.appendStreamedTextAction(sessionId: SessionId, text: String) {
    mutableState.update {
        if (it.sessionId != sessionId || it.streamedText.endsWith(TRUNCATION_MARKER)) {
            it
        } else {
            val remaining = MAX_STREAMED_TEXT_CHARS - it.streamedText.length
            if (text.length <= remaining) {
                it.copy(streamedText = it.streamedText + text)
            } else {
                it.copy(streamedText = it.streamedText + text.take(remaining) + TRUNCATION_MARKER)
            }
        }
    }
}

internal fun CodexSessionController.appendReasoningSummaryAction(event: AgentEvent.ReasoningSummaryDelta) = appendThoughtText(
    event.sessionId,
    event.text,
    event.itemId,
    event.summaryIndex,
)

internal fun CodexSessionController.appendThoughtTextAction(
    sessionId: SessionId,
    text: String,
    itemId: String,
    summaryIndex: Long?,
) {
    mutableState.update {
        if (
            it.sessionId != sessionId ||
            it.streamedReasoning.endsWith(TRUNCATION_MARKER)
        ) {
            it
        } else {
            val separator = if (
                it.streamedReasoning.isNotEmpty() &&
                (it.reasoningItemId != itemId || it.reasoningSummaryIndex != summaryIndex)
            ) "\n\n" else ""
            val delta = separator + text
            val remaining = MAX_STREAMED_TEXT_CHARS - it.streamedReasoning.length
            val next = if (delta.length <= remaining) {
                it.streamedReasoning + delta
            } else {
                it.streamedReasoning + delta.take(remaining) + TRUNCATION_MARKER
            }
            it.copy(
                streamedReasoning = next,
                reasoningItemId = itemId,
                reasoningSummaryIndex = summaryIndex,
            )
        }
    }
}

internal fun CodexSessionController.appendPlanAction(event: AgentEvent.PlanDelta) {
    mutableState.update {
        if (it.sessionId != event.sessionId || it.streamedPlan.endsWith(TRUNCATION_MARKER)) {
            it
        } else {
            val next = if (it.planItemId == null || it.planItemId == event.itemId) {
                it.streamedPlan + event.text
            } else {
                event.text
            }
            it.copy(
                streamedPlan = next.take(MAX_STREAMED_TEXT_CHARS),
                planItemId = event.itemId,
            )
        }
    }
}
