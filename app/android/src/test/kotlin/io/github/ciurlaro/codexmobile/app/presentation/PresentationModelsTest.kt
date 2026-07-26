package io.github.ciurlaro.codexmobile.app.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import io.github.ciurlaro.codexmobile.app.presentation.formatting.effortLabel
import io.github.ciurlaro.codexmobile.app.presentation.formatting.normalizeMarkdownTaskLists
import io.github.ciurlaro.codexmobile.app.presentation.input.shellCommandOrNull
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
import io.github.ciurlaro.codexmobile.app.presentation.model.canonicalPluginSourceId
import io.github.ciurlaro.codexmobile.app.presentation.model.enabledMarketplaceNames
import io.github.ciurlaro.codexmobile.app.presentation.model.extensionSourceItems
import io.github.ciurlaro.codexmobile.app.presentation.model.groupedByPins
import io.github.ciurlaro.codexmobile.app.presentation.model.initialExtensionSourceSelection
import io.github.ciurlaro.codexmobile.app.presentation.model.uninstalledStatus
import io.github.ciurlaro.codexmobile.app.presentation.state.withStreamingAssistant
import io.github.ciurlaro.codexmobile.app.presentation.state.withSubmittedTurn
import io.github.ciurlaro.codexmobile.app.presentation.state.withoutConversation
import io.github.ciurlaro.codexmobile.app.presentation.state.connectorsNeedingOnUseAuthentication
import io.github.ciurlaro.codexmobile.app.presentation.validation.isValidElicitationAnswer
import io.github.ciurlaro.codexmobile.app.ui.chat.shellCommandVisualTransformation
import io.github.ciurlaro.codexmobile.app.ui.extensions.extensionPageSize
import io.github.ciurlaro.codexmobile.app.ui.extensions.pageTokens
import io.github.ciurlaro.codexmobile.core.AgentCapability
import io.github.ciurlaro.codexmobile.core.AgentConversationSummary
import io.github.ciurlaro.codexmobile.core.AgentConnector
import io.github.ciurlaro.codexmobile.core.AgentFormField
import io.github.ciurlaro.codexmobile.core.AgentFormFieldType
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
        assertEquals(ExtensionStatus.UNAVAILABLE, plugin.uninstalledStatus(emptySet(), setOf("documents")))
        assertEquals(null, plugin.uninstalledStatus(setOf("documents"), emptySet()))
        assertEquals(4, extensionPageSize(420f))
        assertEquals(listOf(0, 1, 2, null, 9), pageTokens(page = 1, pageCount = 10))
    }

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
        val submitted = AppUiState(
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

    @Test
    fun invocationAutocompleteRespectsBoundariesMatchesAndDuplicates() {
        val skill = AgentSkill("review", "Review", "Review code", "/skills/review/SKILL.md", AgentSkillScope.SYSTEM, true)
        val plugin = AgentPluginSummary(
            reference = AgentPluginReference("drive@openai-curated", "drive", "openai-curated"),
            displayName = "Drive",
            description = "Drive files",
            installed = true,
            enabled = true,
            installPolicy = AgentPluginInstallPolicy.AVAILABLE,
            authPolicy = AgentPluginAuthPolicy.ON_USE,
            available = true,
        )
        val state = AppUiState(skills = listOf(skill), installedPlugins = listOf(plugin))

        assertEquals(listOf(AgentInvocation.Skill("review", skill.path)), state.copy(draft = "Use \$rev").suggestedInvocations())
        assertEquals(listOf(AgentInvocation.Plugin("drive", plugin.reference.uri)), state.copy(draft = "@").suggestedInvocations())
        assertEquals(listOf(AgentInvocation.Plugin("drive", plugin.reference.uri)), state.copy(draft = "@dri").suggestedInvocations())
        assertEquals(emptyList(), state.copy(draft = "email@example.com").suggestedInvocations())
        assertEquals(emptyList(), state.copy(draft = "@the_iurlix").suggestedInvocations())
        assertEquals(emptyList(), state.copy(
            draft = "\$rev",
            selectedInvocations = listOf(AgentInvocation.Skill("review", skill.path)),
        ).suggestedInvocations())
    }

    @Test
    fun promptInvocationsHideCanonicalNamespacesButKeepThemSearchable() {
        val gmail = AgentSkill(
            name = "gmail:gmail",
            displayName = "Gmail:gmail",
            description = "Search and draft email",
            path = "/plugins/gmail/skills/gmail/SKILL.md",
            scope = AgentSkillScope.PLUGIN,
            enabled = true,
        )
        val triage = AgentSkill(
            name = "gmail:gmail-inbox-triage",
            displayName = "Gmail:gmail inbox triage",
            description = "Triage the inbox",
            path = "/plugins/gmail/skills/gmail-inbox-triage/SKILL.md",
            scope = AgentSkillScope.PLUGIN,
            enabled = true,
        )
        val state = AppUiState(skills = listOf(gmail, triage))

        assertEquals("Gmail", state.promptInvocation(AgentInvocation.Skill(gmail.name, gmail.path)).title)
        val triageItem = state.promptInvocation(AgentInvocation.Skill(triage.name, triage.path))
        assertEquals("Inbox triage", triageItem.title)
        assertEquals("Gmail", triageItem.provider)
        assertEquals(
            listOf(AgentInvocation.Skill(triage.name, triage.path)),
            state.copy(draft = "\$triage").suggestedInvocations(),
        )
        assertEquals(
            AgentInvocation.Skill(gmail.name, gmail.path),
            state.copy(draft = "\$gmail:gmail").suggestedInvocations().first(),
        )
    }

    @Test
    fun recentPromptInvocationsAreUniqueNewestFirstAndBounded() {
        val recent = emptyList<String>()
            .withRecentInvocation("one")
            .withRecentInvocation("two")
            .withRecentInvocation("three")
            .withRecentInvocation("four")
            .withRecentInvocation("two")
            .withRecentInvocation("five")

        assertEquals(listOf("five", "two", "four", "three"), recent)
    }

    @Test
    fun elicitationValidationRejectsMalformedAndOutOfRangeValues() {
        val integer = AgentFormField(
            name = "count",
            title = "Count",
            required = true,
            type = AgentFormFieldType.INTEGER,
            minimum = 1.0,
            maximum = 5.0,
        )

        assertTrue(isValidElicitationAnswer(integer, AgentFormValue.Number(3.0)))
        assertFalse(isValidElicitationAnswer(integer, AgentFormValue.Number(3.5)))
        assertFalse(isValidElicitationAnswer(integer, AgentFormValue.Number(6.0)))
        assertFalse(isValidElicitationAnswer(integer, AgentFormValue.Text("3")))
    }

    @Test
    fun onUseAuthenticationOnlySelectsMatchingDisconnectedConnectors() {
        val plugin = AgentPluginSummary(
            reference = AgentPluginReference("drive@openai-curated", "drive", "openai-curated"),
            displayName = "Drive",
            description = "Drive files",
            installed = true,
            enabled = true,
            installPolicy = AgentPluginInstallPolicy.AVAILABLE,
            authPolicy = AgentPluginAuthPolicy.ON_USE,
            available = true,
        )
        val disconnected = AgentConnector(
            "connector",
            "Drive",
            installUrl = "https://example.com/connect",
            pluginNames = listOf("Drive"),
        )
        val unrelated = AgentConnector("mail", "Mail", pluginNames = listOf("Mail"))
        val state = AppUiState(
            selectedInvocations = listOf(AgentInvocation.Plugin("drive", plugin.reference.uri)),
            installedPlugins = listOf(plugin),
            connectors = listOf(disconnected, unrelated),
        )

        assertEquals(listOf(disconnected), state.connectorsNeedingOnUseAuthentication())
    }
}
