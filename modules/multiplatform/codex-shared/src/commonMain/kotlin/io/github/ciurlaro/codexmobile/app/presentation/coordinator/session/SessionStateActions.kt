package io.github.ciurlaro.codexmobile.app.presentation.viewmodel

import io.github.ciurlaro.codexmobile.app.presentation.state.withStreamingAssistant
import io.github.ciurlaro.codexmobile.app.session.agent.CodexSessionState
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun AppViewModel.applySessionStateAction(
    session: CodexSessionState,
    notificationVisible: Boolean,
) {
    if (session.isAuthenticated && !preferenceState.hadAuthenticatedSession) {
        preferenceState = preferenceState.copy(hadAuthenticatedSession = true)
        scope.launch { uiPreferences.setHadAuthenticatedSession(true) }
    }
    when {
        session.isAuthenticated -> setAuthenticationHandoffPending(false)
        session.signInUrl != null -> setAuthenticationHandoffPending(true)
        session.terminal || session.diagnosticCode != null -> setAuthenticationHandoffPending(false)
    }
    val before = mutableState.value
    val finishedTurn = before.isTurnActive && !session.isTurnActive
    val assistantId = activeAssistantMessageId
    val restoreSelection = session.sessionId != null &&
        pendingConversationId == session.sessionId &&
        selectionRestoredSessionId != session.sessionId
    if (restoreSelection) selectionRestoredSessionId = session.sessionId
    mutableState.update { current ->
        val messages = assistantId?.let {
            current.messages.withStreamingAssistant(
                assistantMessageId = it,
                text = session.streamedText,
                reasoning = session.streamedReasoning,
                plan = session.streamedPlan,
                planProgress = session.planProgress,
                hookActivities = session.hookActivities,
                isStreaming = session.isTurnActive,
                exitCode = session.shellExitCode,
            )
        } ?: current.messages
        current.copy(
            statusMessage = session.statusMessage,
            streamedText = session.streamedText,
            streamedReasoning = session.streamedReasoning,
            streamedPlan = session.streamedPlan,
            planProgress = session.planProgress,
            hookActivities = session.hookActivities,
            sessionId = session.sessionId,
            isAuthenticated = session.isAuthenticated,
            messages = messages,
            selectedModel = if (restoreSelection) session.activeModel ?: current.selectedModel
            else current.selectedModel,
            selectedEffort = if (restoreSelection) session.activeEffort ?: current.selectedEffort
            else current.selectedEffort,
            selectedSpeedTier = if (restoreSelection) {
                session.activeServiceTier ?: current.selectedSpeedTier
            } else current.selectedSpeedTier,
            signInUrl = session.signInUrl,
            isAuthenticationInProgress = current.isAuthenticationInProgress &&
                !session.isAuthenticated &&
                session.signInUrl == null &&
                session.diagnosticCode == null &&
                !session.terminal,
            codexApproval = session.pendingApproval,
            pendingElicitation = session.pendingElicitation,
            isTurnActive = session.isTurnActive,
            isBackgroundActive = !session.terminal,
            isBackgroundNotificationVisible = notificationVisible,
        )
    }
    if (finishedTurn) {
        activeAssistantMessageId = null
        refreshConversations()
    }
    if (restoreSelection) persistSelection()
}
