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
import io.github.ciurlaro.codexmobile.app.presentation.model.CODEX_MOBILE_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.OPENAI_PLUGIN_SOURCE_ID
import io.github.ciurlaro.codexmobile.app.presentation.model.CustomExtensionSource
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.ExtensionNotice
import io.github.ciurlaro.codexmobile.app.presentation.model.PluginCatalogStatus
import io.github.ciurlaro.codexmobile.app.presentation.model.canonicalPluginSourceId
import io.github.ciurlaro.codexmobile.app.presentation.model.afterExpiry
import io.github.ciurlaro.codexmobile.app.presentation.model.enabledMarketplaceNames
import io.github.ciurlaro.codexmobile.app.presentation.model.extensionSourceItems
import io.github.ciurlaro.codexmobile.app.presentation.model.groupedByPins
import io.github.ciurlaro.codexmobile.app.presentation.model.initialExtensionSourceSelection
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
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentFormField
import io.github.ciurlaro.codexmobile.core.AgentFormFieldType
import io.github.ciurlaro.codexmobile.core.AgentFormOption
import io.github.ciurlaro.codexmobile.core.AgentFormValue
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginInstallPolicy
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginSummary
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillScope
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import io.github.ciurlaro.codexmobile.core.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresentationModelsTest {
    @Test
    fun freshPluginSourcesEnableOnlyCodexMobile() {
        val selection = initialExtensionSourceSelection(null, null, appWasUpgraded = false)

        assertEquals(setOf(CODEX_MOBILE_PLUGIN_SOURCE_ID, OPENAI_PLUGIN_SOURCE_ID), selection.knownIds)
        assertEquals(setOf(CODEX_MOBILE_PLUGIN_SOURCE_ID), selection.enabledIds)
        assertEquals(
            listOf("Codex Mobile" to true, "OpenAI curated" to false),
            extensionSourceItems(selection).map { it.displayName to it.enabled },
        )
    }

    @Test
    fun savedPluginSourceChoicesSurviveUpgradesAndOpenAiAliasesNormalize() {
        val selection = initialExtensionSourceSelection(
            savedKnownIds = setOf("codex-mobile", "openai-curated", "team-marketplace"),
            savedEnabledIds = setOf("team-marketplace"),
            appWasUpgraded = true,
        )

        assertEquals(OPENAI_PLUGIN_SOURCE_ID, canonicalPluginSourceId("openai-curated"))
        assertEquals(setOf("team-marketplace"), selection.enabledIds)
        assertEquals(3, extensionSourceItems(selection).size)
    }

    @Test
    fun customExtensionSourceControlsItsSkillAndPluginCatalogsTogether() {
        val custom = CustomExtensionSource(
            id = "github:documents",
            url = "https://github.com/team/documents",
            marketplaceName = "team-marketplace",
            supportsSkills = true,
            supportsPlugins = true,
        )
        val selection = ExtensionSourceSelection(
            knownIds = setOf(custom.id),
            enabledIds = setOf(custom.id),
            customSources = listOf(custom),
        )

        assertEquals(setOf("team-marketplace"), selection.enabledMarketplaceNames())
        assertEquals("Skills + Plugins", extensionSourceItems(selection).single().capabilityLabel)
    }

    @Test
    fun customExtensionSourcesSurviveMissingLegacyPreferenceKeys() {
        val custom = CustomExtensionSource(
            id = "github:documents",
            url = "https://github.com/team/documents",
            marketplaceName = "team-marketplace",
            supportsSkills = true,
            supportsPlugins = true,
        )

        val selection = initialExtensionSourceSelection(
            savedKnownIds = null,
            savedEnabledIds = null,
            savedCustomSources = listOf(custom),
            appWasUpgraded = false,
        )

        assertTrue(custom.id in selection.knownIds)
        assertTrue(custom.id in selection.enabledIds)
    }

    @Test
    fun extensionStatusAndPaginationStayDeterministic() {
        val plugin = AgentPluginSummary(
            reference = AgentPluginReference("documents", "documents", "codex-mobile"),
            displayName = "Documents",
            description = "Read documents",
            installed = false,
            enabled = false,
            installPolicy = AgentPluginInstallPolicy.AVAILABLE,
            authPolicy = AgentPluginAuthPolicy.ON_USE,
            available = true,
        )

        assertEquals(ExtensionStatus.UNINSTALLED, plugin.uninstalledStatus(emptySet(), emptySet()))
        assertEquals("Market", ExtensionStatus.UNINSTALLED.label)
        assertEquals("Setup pending", ExtensionStatus.SETUP_PENDING.label)
        assertEquals(ExtensionStatus.UNAVAILABLE, plugin.uninstalledStatus(emptySet(), setOf("documents")))
        assertEquals(null, plugin.uninstalledStatus(setOf("documents"), emptySet()))
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
