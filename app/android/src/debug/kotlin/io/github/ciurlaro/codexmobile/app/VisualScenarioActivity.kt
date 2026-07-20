package io.github.ciurlaro.codexmobile.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentMessageRole
import io.github.ciurlaro.codexmobile.core.AgentModel
import io.github.ciurlaro.codexmobile.core.SessionId

class VisualScenarioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        window.setWindowAnimations(0)
        val scenario = VisualScenario.fromId(intent.getStringExtra(EXTRA_SCENARIO))
        setContent { CodexMobileApp(state = scenario.state(), onEvent = {}) }
    }

    companion object {
        const val EXTRA_SCENARIO = "visualScenario"
    }
}

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

    fun state(): MainUiState = when (this) {
        FRESH -> baseState()
        DRAWER -> conversationState().copy(drawerOpen = true)
        FOCUSED_COMPOSER -> baseState().copy(
            draft = "Draft a concise implementation plan\nwith verification steps.",
        )
        EFFORT_SELECTOR -> baseState().copy(
            draft = "Draft a concise implementation plan\nwith verification steps.",
            popup = ChatPopup.EFFORT,
        )
        MODEL_SELECTOR -> baseState().copy(
            draft = "Draft a concise implementation plan\nwith verification steps.",
            popup = ChatPopup.MODEL,
        )
        SETTINGS -> conversationState().copy(destination = AppDestination.SETTINGS)
        TAGGED_CONVERSATION -> conversationState(messages = listOf(TAGGED_USER_MESSAGE))
        THINKING -> conversationState(
            messages = listOf(TAGGED_USER_MESSAGE, THINKING_CODEX_MESSAGE),
        ).copy(turnActive = true, status = "Thinking")
        COMPLETED -> conversationState(
            messages = listOf(TAGGED_USER_MESSAGE, COMPLETED_CODEX_MESSAGE),
        )
    }

    companion object {
        fun fromId(id: String?): VisualScenario = entries.singleOrNull { it.id == id } ?: FRESH
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
    text = "The stable release focuses on platform reliability, privacy, and developer tooling.",
    model = MODELS.first().id,
    effort = "high",
)

private val THINKING_CODEX_MESSAGE = ChatMessage(
    id = "visual-codex-message",
    role = AgentMessageRole.CODEX,
    text = "",
    model = MODELS.first().id,
    effort = "high",
    streaming = true,
)

private fun baseState() = MainUiState(
    status = "Ready",
    authenticated = true,
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
