package io.github.ciurlaro.codexmobile.app.ui.shell

import io.github.ciurlaro.codexmobile.app.presentation.model.AppScreen
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatSelector
import io.github.ciurlaro.codexmobile.app.ui.theme.AppTheme
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentHookActivity
import io.github.ciurlaro.codexmobile.agent.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentPlanProgress
import io.github.ciurlaro.codexmobile.agent.AgentPlanStep
import io.github.ciurlaro.codexmobile.agent.AgentPlanStepStatus
import io.github.ciurlaro.codexmobile.agent.SessionId

enum class VisualScenario(val id: String) {
    FRESH("fresh"),
    DRAWER("drawer"),
    FOCUSED_COMPOSER("focused-composer"),
    EFFORT_SELECTOR("effort-selector"),
    MODEL_SELECTOR("model-selector"),
    SETTINGS("settings"),
    TAGGED_CONVERSATION("tagged-conversation"),
    THINKING("thinking"),
    COMPLETED("completed");

    fun state(): AppUiState = when (this) {
        FRESH -> baseState()
        DRAWER -> conversationState().copy(isHistoryOpen = true)
        FOCUSED_COMPOSER -> baseState().copy(
            draft = "Draft a concise implementation plan\nwith verification steps.",
        )
        EFFORT_SELECTOR -> baseState().copy(
            draft = "Draft a concise implementation plan\nwith verification steps.",
            activeSelector = ChatSelector.EFFORT,
        )
        MODEL_SELECTOR -> baseState().copy(
            draft = "Draft a concise implementation plan\nwith verification steps.",
            activeSelector = ChatSelector.MODEL,
        )
        SETTINGS -> conversationState().copy(screen = AppScreen.SETTINGS)
        TAGGED_CONVERSATION -> conversationState(messages = listOf(TAGGED_USER_MESSAGE))
        THINKING -> conversationState(
            messages = listOf(TAGGED_USER_MESSAGE, THINKING_CODEX_MESSAGE),
        ).copy(isTurnActive = true, statusMessage = "Thinking")
        COMPLETED -> conversationState(
            messages = listOf(TAGGED_USER_MESSAGE, COMPLETED_CODEX_MESSAGE),
        )
    }

}

private val MODELS = listOf(
    AgentModel(
        id = "codex-standard",
        displayName = "Codex Standard",
        description = "Balanced coding assistance",
        supportedEfforts = listOf("low", "medium", "high", "xhigh"),
        defaultEffort = "high",
        isDefault = true,
    ),
    AgentModel(
        id = "codex-fast",
        displayName = "Codex Fast",
        description = "Fast responses for focused tasks",
        supportedEfforts = listOf("low", "medium", "high"),
        defaultEffort = "medium",
        isDefault = false,
    ),
)

private val ACTIVE_SESSION = SessionId("visual-active")

private val CONVERSATIONS = listOf(
    AgentConversationSummary(ACTIVE_SESSION, "Review the Android chat flow", 3L),
    AgentConversationSummary(SessionId("visual-history-2"), "Prepare a release checklist", 2L),
    AgentConversationSummary(SessionId("visual-history-1"), "Explain streaming cancellation", 1L),
)

private val TAGGED_USER_MESSAGE = ChatMessage(
    id = "visual-user-message",
    role = AgentMessageRole.USER,
    text = "Summarize the latest stable Android release notes.",
    capabilities = setOf(AgentCapability.WEB_SEARCH),
    model = MODELS.first().id,
    effort = "high",
)

private val COMPLETED_CODEX_MESSAGE = ChatMessage(
    id = "visual-codex-message",
    role = AgentMessageRole.CODEX,
    text = """The stable release focuses on platform reliability. Inline math such as ${'$'}E = mc^2${'$'} is rendered with the answer.

\[
x = \frac{12}{0.8} = 15
\]""",
    model = MODELS.first().id,
    effort = "high",
)

private val THINKING_CODEX_MESSAGE = ChatMessage(
    id = "visual-codex-message",
    role = AgentMessageRole.CODEX,
    text = "",
    reasoning = "I’ll verify the current behavior, make the smallest safe change, then run the focused checks.",
    planProgress = AgentPlanProgress(
        explanation = "Brief plan to answer your question",
        steps = listOf(
            AgentPlanStep("Inspect the Android release notes", AgentPlanStepStatus.COMPLETED),
            AgentPlanStep("Compare behavior with the current app", AgentPlanStepStatus.IN_PROGRESS),
            AgentPlanStep("Summarize the verified changes", AgentPlanStepStatus.PENDING),
        ),
    ),
    hookActivities = listOf(
        AgentHookActivity(
            id = "visual-hook",
            eventName = "After tool use",
            handlerType = "command",
            status = AgentHookRunStatus.COMPLETED,
            statusMessage = "Hook completed",
        ),
    ),
    model = MODELS.first().id,
    effort = "high",
    isStreaming = true,
)

private fun baseState() = AppUiState(
    statusMessage = "Ready",
    isAuthenticated = true,
    conversations = CONVERSATIONS,
    models = MODELS,
    selectedModel = MODELS.first().id,
    selectedEffort = "high",
)

private fun conversationState(
    messages: List<ChatMessage> = listOf(TAGGED_USER_MESSAGE, COMPLETED_CODEX_MESSAGE),
) = baseState().copy(
    sessionId = ACTIVE_SESSION,
    messages = messages,
)
