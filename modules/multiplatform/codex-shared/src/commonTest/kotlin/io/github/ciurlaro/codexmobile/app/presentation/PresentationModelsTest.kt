package io.github.ciurlaro.codexmobile.app.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import io.github.ciurlaro.codexmobile.app.presentation.formatting.effortLabel
import io.github.ciurlaro.codexmobile.app.presentation.formatting.distinctThoughts
import io.github.ciurlaro.codexmobile.app.presentation.formatting.normalizeMarkdownTaskLists
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.input.planCommandOrNull
import io.github.ciurlaro.codexmobile.app.presentation.invocation.promptInvocation
import io.github.ciurlaro.codexmobile.app.presentation.invocation.suggestedInvocations
import io.github.ciurlaro.codexmobile.app.presentation.invocation.withRecentInvocation
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.afterExpiry
import io.github.ciurlaro.codexmobile.app.presentation.model.groupedByPins
import io.github.ciurlaro.codexmobile.app.presentation.model.reconcilePendingPluginSetups
import io.github.ciurlaro.codexmobile.app.presentation.model.uninstalledStatus
import io.github.ciurlaro.codexmobile.app.presentation.state.withStreamingAssistant
import io.github.ciurlaro.codexmobile.app.presentation.state.withSubmittedTurn
import io.github.ciurlaro.codexmobile.app.presentation.state.withNewChat
import io.github.ciurlaro.codexmobile.app.presentation.state.withoutConversation
import io.github.ciurlaro.codexmobile.app.presentation.state.connectorsNeedingOnUseAuthentication
import io.github.ciurlaro.codexmobile.app.presentation.validation.isValidElicitationAnswer
import io.github.ciurlaro.codexmobile.app.ui.chat.shellCommandVisualTransformation
import io.github.ciurlaro.codexmobile.app.ui.chat.planCommandVisualTransformation
import io.github.ciurlaro.codexmobile.app.ui.extensions.extensionPageSize
import io.github.ciurlaro.codexmobile.app.ui.extensions.pageTokens
import io.github.ciurlaro.codexmobile.app.ui.extensions.pluginEmptyMessage
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentFormField
import io.github.ciurlaro.codexmobile.agent.AgentFormFieldType
import io.github.ciurlaro.codexmobile.agent.AgentFormOption
import io.github.ciurlaro.codexmobile.agent.AgentFormValue
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginSummary
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillScope
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresentationModelsTest {
    @Test
    fun extensionStatusAndPaginationStayDeterministic() {
        val plugin = AgentPluginSummary(
            reference = AgentPluginReference("calendar", "calendar", "official"),
            displayName = "Calendar",
            description = "Read calendar events",
            installed = false,
            enabled = false,
            installPolicy = AgentPluginInstallPolicy.AVAILABLE,
            authPolicy = AgentPluginAuthPolicy.ON_USE,
            available = true,
        )

        assertEquals(ExtensionStatus.UNINSTALLED, plugin.uninstalledStatus(emptySet(), emptySet()))
        assertEquals("Market", ExtensionStatus.UNINSTALLED.label)
        assertEquals("Setup pending", ExtensionStatus.SETUP_PENDING.label)
        assertEquals(ExtensionStatus.UNAVAILABLE, plugin.uninstalledStatus(emptySet(), setOf("calendar")))
        assertEquals(null, plugin.uninstalledStatus(setOf("calendar"), emptySet()))
        assertEquals(4, extensionPageSize(420f))
        assertEquals(listOf(0, 1, 2, null, 9), pageTokens(page = 1, pageCount = 10))
        assertEquals(
            "Connecting to Codex…",
            pluginEmptyMessage(AppUiState(pluginCatalogStatus = PluginCatalogStatus.CONNECTING), ""),
        )
        assertEquals(
            "No installed plugins",
            pluginEmptyMessage(AppUiState(pluginCatalogStatus = PluginCatalogStatus.LIVE), ""),
        )
    }

    @Test
    fun effortLabelsAreCentralizedAndUnknownValuesRemainReadable() {
        assertEquals("Extra High", effortLabel("xhigh"))
        assertEquals("Ultra", effortLabel("ultra"))
        assertEquals("Custom", effortLabel("custom"))
    }

    @Test
    fun distinctThoughtsPreserveTheirOwnContent() {
        assertEquals(
            listOf("Inspecting files", "Comparing\nresults", "Writing answer"),
            "Inspecting files\n\nComparing\nresults\n\n\nWriting answer".distinctThoughts(),
        )
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
    fun planShortcutIsLeadingCaseInsensitiveAndKeepsItsPrompt() {
        assertEquals("Design the fix", "/PLAN  Design the fix".planCommandOrNull()?.prompt)
        assertEquals("", "/plan".planCommandOrNull()?.prompt)
        assertEquals(null, "use /plan here".planCommandOrNull())
        assertEquals(null, "/planner".planCommandOrNull())

        val transformed = planCommandVisualTransformation.filter(AnnotatedString("/plan inspect"))
        assertEquals("/plan inspect", transformed.text.text)
        assertEquals(Color(0xFFFFA94D), transformed.text.spanStyles.single().item.color)
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

}
