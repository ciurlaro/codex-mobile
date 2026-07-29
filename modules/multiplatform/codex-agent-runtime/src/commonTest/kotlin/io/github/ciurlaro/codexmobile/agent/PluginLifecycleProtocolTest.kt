package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.agent.*
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalResult
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class PluginLifecycleProtocolTest : SkillsPluginsProtocolTestBase() {
    @Test
    fun retryableProviderCleanupKeepsItsPluginAndCodeInstalledButDisabled(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val provider = object : PluginProviderHost {
            override suspend fun install(plugin: AgentPluginReference, mcpServerNames: Set<String>) =
                ProviderInstallDisposition.READY
            override fun manages(pluginId: String) = true
            override suspend fun prepareRemoval(pluginId: String): ProviderRemovalResult {
                events += "provider-prepare"
                return ProviderRemovalResult.retry("Remote revocation could not be confirmed")
            }
            override suspend fun remove(pluginId: String) { events += "provider-remove" }
        }
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "config/value/write" -> { events += "plugin-disable"; server.respond(message.id, buildJsonObject {}) }
                "plugin/uninstall" -> { events += "plugin-uninstall"; server.respond(message.id, buildJsonObject {}) }
            }
        }

        val result = CodexAgentClient(
            { runtime },
            requestTimeoutMillis = 1_000,
            providerHost = provider,
        ).use { client ->
            client.uninstallPlugin(AgentPluginReference("sample@catalog", "sample", "catalog", "/marketplace"))
        }

        assertFalse(result.completed)
        assertEquals("Remote revocation could not be confirmed", result.message)
        assertEquals(listOf("plugin-disable", "provider-prepare"), events)
    }

    @Test
    fun preparedProviderRemovalResumesAfterRestartWithoutDelayingInstalledPlugins(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val removed = CountDownLatch(1)
        val reference = AgentPluginReference("drive@openai-curated", "drive", "openai-curated", "/marketplace")
        val provider = object : PluginProviderHost {
            override suspend fun install(plugin: AgentPluginReference, mcpServerNames: Set<String>) =
                ProviderInstallDisposition.NOT_REQUIRED
            override fun preparedRemovals() = listOf(reference)
            override fun manages(pluginId: String) = true
            override suspend fun prepareRemoval(pluginId: String) = ProviderRemovalResult.ready()
            override suspend fun remove(pluginId: String) {
                events += "provider-remove"
                removed.countDown()
            }
        }
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> server.respond(message.id, pluginList(installed = true))
                "plugin/uninstall" -> {
                    events += "plugin-uninstall"
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }

        CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000, providerHost = provider).use { client ->
            assertTrue(client.listInstalledPlugins("/workspace").plugins.single().installed)
            assertTrue(removed.await(1, TimeUnit.SECONDS))
        }

        assertEquals(listOf("plugin-uninstall", "provider-remove"), events)
    }

    @Test
    fun pendingProviderInstallCompletesImmediatelyAfterRestartAuthentication(): Unit = runBlocking {
        val reference = AgentPluginReference("drive@openai-curated", "drive", "openai-curated", "/marketplace")
        val completed = CountDownLatch(1)
        val provider = object : PluginProviderHost {
            override suspend fun install(plugin: AgentPluginReference, mcpServerNames: Set<String>) =
                ProviderInstallDisposition.NOT_REQUIRED
            override fun pendingInstalls() = listOf(reference)
            override fun manages(pluginId: String) = true
            override fun mcpServerNames(pluginId: String) = setOf("drive")
            override fun installCompleted(pluginId: String) = completed.countDown()
            override suspend fun prepareRemoval(pluginId: String) = ProviderRemovalResult.ready()
            override suspend fun remove(pluginId: String) = Unit
        }
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("account") { put("type", "chatgpt") }
                })
                "plugin/read" -> server.respond(message.id, pluginDetail(installed = false))
                "config/value/write" -> server.respond(message.id, buildJsonObject {})
                "plugin/install" -> server.respond(message.id, buildJsonObject {
                    put("authPolicy", "ON_USE")
                    putJsonArray("appsNeedingAuth") {}
                })
            }
        }

        CodexAgentClient(
            runtimeFactory = { runtime },
            requestTimeoutMillis = 1_000,
            providerHost = provider,
        ).use { client ->
            client.authenticate()
            assertTrue(completed.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun encodesOrderedDeduplicatedSkillAndPluginInvocations() {
        val skill = AgentInvocation.Skill("review", "/skills/review/SKILL.md")
        val plugin = AgentInvocation.Plugin("drive", "plugin://drive@openai-curated")
        val input = turnInput(
            AgentTurnRequest(
                prompt = "Check this",
                invocations = listOf(skill, plugin, skill),
            ),
        )

        assertEquals("\$review\n@drive\n\nCheck this", assertIs<UserInputTextUserInput>(input[0]).text)
        assertEquals("/skills/review/SKILL.md", assertIs<UserInputSkillUserInput>(input[1]).path)
        assertEquals("plugin://drive@openai-curated", assertIs<UserInputMentionUserInput>(input[2]).path)
    }

    @Test
    fun decodesSupportedElicitationFormsAndRejectsUnsafeUrls() {
        val elicitation = parseElicitation(
            "7",
            Json.decodeFromJsonElement(McpServerElicitationRequestParams.serializer(), buildJsonObject {
                put("serverName", "drive")
                put("threadId", "thread-1")
                put("message", "Choose")
                put("mode", "form")
                putJsonObject("requestedSchema") {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("folder")) }
                    putJsonObject("properties") {
                        putJsonObject("folder") {
                            put("type", "string")
                            put("title", "Folder")
                        }
                        putJsonObject("format") {
                            put("type", "string")
                            putJsonArray("enum") { add(JsonPrimitive("pdf")); add(JsonPrimitive("docx")) }
                        }
                        putJsonObject("notify") { put("type", "boolean") }
                    }
                }
            }),
        )

        assertEquals(listOf(AgentFormFieldType.STRING, AgentFormFieldType.SINGLE_SELECT, AgentFormFieldType.BOOLEAN),
            elicitation.form!!.map { it.type })
        assertTrue(elicitation.form!!.first().required)
        assertFailsWith<IllegalArgumentException> { requireSafeAuthUrl("http://192.168.1.2/login") }
        assertEquals("http://127.0.0.1:9876/callback", requireSafeAuthUrl("http://127.0.0.1:9876/callback"))
    }

    @Test
    fun mapsPlanQuestionsToSelectableMobileFormFields() {
        val elicitation = parseUserInputRequest(
            "9",
            ToolRequestUserInputParams(
                itemId = "item-1",
                threadId = "thread-1",
                turnId = "turn-1",
                questions = listOf(
                    ToolRequestUserInputQuestion(
                        header = "Dates",
                        id = "dates",
                        question = "Are your dates flexible?",
                        isOther = true,
                        options = listOf(ToolRequestUserInputOption("Any week works", "Flexible")),
                    ),
                ),
            ),
        )

        val field = elicitation.form!!.single()
        assertEquals(AgentFormFieldType.SINGLE_SELECT, field.type)
        assertEquals("Any week works", field.options.single().description)
        assertTrue(field.allowOther)
    }

}
