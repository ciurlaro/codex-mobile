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
