package io.github.ciurlaro.codexmobile.app.presentation

import io.github.ciurlaro.codexmobile.app.presentation.invocation.*
import io.github.ciurlaro.codexmobile.app.presentation.model.*
import io.github.ciurlaro.codexmobile.app.presentation.state.*
import io.github.ciurlaro.codexmobile.app.presentation.validation.*
import io.github.ciurlaro.codexmobile.agent.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresentationInvocationModelsTest {
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

        val choice = AgentFormField(
            name = "dates",
            title = "Dates",
            required = true,
            type = AgentFormFieldType.SINGLE_SELECT,
            options = listOf(AgentFormOption("fixed")),
            allowOther = true,
        )
        assertTrue(isValidElicitationAnswer(choice, AgentFormValue.Text("another week")))
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

    @Test
    fun pendingPluginSetupSurvivesRestartUntilEveryConnectorIsAccessible() {
        val pending = mapOf("drive" to setOf("account", "files"), "removed" to setOf("legacy"))
        val connectors = listOf(
            AgentConnector("account", "Account", isAccessible = true),
            AgentConnector("files", "Files", installUrl = "https://example.com/connect"),
        )

        assertEquals(
            mapOf("drive" to setOf("files")),
            reconcilePendingPluginSetups(pending, connectors, installedPluginIds = setOf("drive")),
        )
    }

    @Test
    fun expiringNoticeCannotClearANewerNotice() {
        val old = ExtensionNotice("Installed")
        val current = ExtensionNotice("Setup still required", isError = true)

        assertEquals(null, old.afterExpiry(old))
        assertEquals(current, current.afterExpiry(old))
    }

    @Test
    fun pendingOnInstallConnectorBlocksUseUntilItIsConnected() {
        val plugin = AgentPluginSummary(
            reference = AgentPluginReference("drive@openai-curated", "drive", "openai-curated"),
            displayName = "Drive",
            description = "Drive files",
            installed = true,
            enabled = true,
            installPolicy = AgentPluginInstallPolicy.AVAILABLE,
            authPolicy = AgentPluginAuthPolicy.ON_INSTALL,
            available = true,
        )
        val disconnected = AgentConnector(
            "account",
            "OpenAI account",
            installUrl = "https://example.com/connect",
        )
        val state = AppUiState(
            selectedInvocations = listOf(AgentInvocation.Plugin("drive", plugin.reference.uri)),
            installedPlugins = listOf(plugin),
            connectors = listOf(disconnected),
            pendingPluginSetups = mapOf(plugin.reference.id to setOf(disconnected.id)),
        )

        assertEquals(listOf(disconnected), state.connectorsNeedingOnUseAuthentication())
    }
}
