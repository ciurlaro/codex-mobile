package io.github.ciurlaro.codexmobile.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatModelsTest {
    @Test
    fun effortLabelsAreCentralizedAndUnknownValuesRemainReadable() {
        assertEquals("Extra High", effortLabel("xhigh"))
        assertEquals("Ultra", effortLabel("ultra"))
        assertEquals("Custom", effortLabel("custom"))
    }

    @Test
    fun plusPickerUsesTheTypedCapabilityCatalog() {
        assertEquals("web_search", AgentCapability.WEB_SEARCH.id)
        assertEquals("Web search", AgentCapability.WEB_SEARCH.displayLabel)
    }

    @Test
    fun onlyALeadingBangSelectsShellMode() {
        assertEquals("ls -la", "!  ls -la  ".shellCommandOrNull())
        assertEquals("", "!".shellCommandOrNull())
        assertEquals(null, "show !help".shellCommandOrNull())
        assertEquals(null, " !ls".shellCommandOrNull())
    }

    @Test
    fun shellBangIsBlueAndSeparatedWithoutChangingCursorOffsets() {
        val transformed = shellCommandVisualTransformation.filter(AnnotatedString("!ls"))

        assertEquals("!  ls", transformed.text.text)
        assertEquals(Color(0xFF3F83F8), transformed.text.spanStyles.single().item.color)
        assertEquals(3, transformed.offsetMapping.originalToTransformed(1))
        assertEquals(1, transformed.offsetMapping.transformedToOriginal(2))
        assertEquals(3, transformed.offsetMapping.transformedToOriginal(5))
    }

    @Test
    fun bareTaskMarkersBecomeMarkdownTasksWithoutChangingCodeFences() {
        val markdown = """
            [ ] Unchecked
            [x] Checked
            - [X] Already a task
            ```text
            [ ] Code sample
            ```
        """.trimIndent()

        assertEquals(
            """
                - [ ] Unchecked
                - [x] Checked
                - [X] Already a task
                ```text
                [ ] Code sample
                ```
            """.trimIndent(),
            markdown.normalizeMarkdownTaskLists(),
        )
    }

    @Test
    fun pinnedConversationsFormTheirOwnStableGroup() {
        val first = AgentConversationSummary(SessionId("first"), "First", 3)
        val second = AgentConversationSummary(SessionId("second"), "Second", 2)
        val third = AgentConversationSummary(SessionId("third"), "Third", 1)

        val groups = listOf(first, second, third).groupedByPins(setOf("second"))

        assertEquals(listOf(second), groups.pinned)
        assertEquals(listOf(first, third), groups.recent)
    }

    @Test
    fun stateTransformationsResetAndRemoveConversationsConsistently() {
        val session = SessionId("active")
        val conversation = AgentConversationSummary(session, "Active", 1)
        val submitted = MainUiState(
            isAuthenticated = true,
            conversations = listOf(conversation),
            pinnedConversationIds = setOf(session.value),
            draft = "hello",
        ).withSubmittedTurn(
            AgentTurnRequest(prompt = "hello", clientMessageId = "message"),
            assistantMessageId = "assistant",
            shellCommand = null,
        )

        assertEquals(listOf("user-message", "assistant"), submitted.messages.map(ChatMessage::id))
        assertEquals("", submitted.draft)

        val removed = submitted.copy(sessionId = session).withoutConversation(session)
        assertEquals(emptyList(), removed.conversations)
        assertEquals(emptySet(), removed.pinnedConversationIds)
        assertEquals(null, removed.sessionId)
        assertEquals("Conversation deleted", removed.statusMessage)
    }

    @Test
    fun completedEmptyStreamingPlaceholderIsRemoved() {
        val messages = listOf(
            ChatMessage(
                id = "assistant",
                role = io.github.ciurlaro.codexmobile.core.AgentMessageRole.CODEX,
                text = "",
                isStreaming = true,
            ),
        )

        assertEquals(
            emptyList(),
            messages.withStreamingAssistant("assistant", "", isStreaming = false, exitCode = null),
        )
    }
}
