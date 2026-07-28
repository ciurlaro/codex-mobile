package io.github.ciurlaro.codexmobile.app.presentation

import io.github.ciurlaro.codexmobile.app.presentation.invocation.*
import io.github.ciurlaro.codexmobile.app.presentation.model.*
import io.github.ciurlaro.codexmobile.app.presentation.state.*
import io.github.ciurlaro.codexmobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresentationStateModelsTest {
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
    fun planTurnsStayMarkedAndNewChatsReturnToDefaultMode() {
        val planned = AppUiState(collaborationMode = AgentCollaborationMode.PLAN).withSubmittedTurn(
            request = AgentTurnRequest(
                prompt = "Plan a trip",
                clientMessageId = "codex-mobile:plan:test",
                collaborationMode = AgentCollaborationMode.PLAN,
            ),
            assistantMessageId = "assistant",
            shellCommand = null,
        )

        assertEquals(AgentCollaborationMode.PLAN, planned.messages.first().collaborationMode)
        assertEquals(AgentCollaborationMode.DEFAULT, planned.withNewChat().collaborationMode)
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

}
