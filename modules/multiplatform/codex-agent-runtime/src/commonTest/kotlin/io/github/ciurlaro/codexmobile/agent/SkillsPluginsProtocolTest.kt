package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.PluginListResponse
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.McpServerElicitationRequestParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ToolRequestUserInputOption
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ToolRequestUserInputParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ToolRequestUserInputQuestion
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.UserInputMentionUserInput
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.UserInputSkillUserInput
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.UserInputTextUserInput
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalResult

import io.github.ciurlaro.codexmobile.agent.AgentFormFieldType
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val REMOTE_PLUGIN_ID = "plugin_asdk_app_69a1d78e929881919bba0dbda1f6436d"

class SkillsPluginsProtocolTest : SkillsPluginsProtocolTestBase() {
    @Test
    fun githubMarketplaceSourceUsesAppServerMarketplaceMethodWithoutAssumingABranch(): Unit = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "marketplace/add" -> {
                    requests += message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, buildJsonObject {
                        put("alreadyAdded", false)
                        put("installedRoot", "/marketplace")
                        put("marketplaceName", "plugins")
                    })
                }
            }
        }

        CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000).use { client ->
            client.addPluginMarketplace("https://github.com/owner/plugins")
            client.addPluginMarketplace("https://github.com/owner/plugins/tree/release/catalog/mobile")
            client.addPluginMarketplace("/data/user/0/app/no_backup/codex/mobile-marketplaces/snapshot")
        }

        assertEquals("https://github.com/owner/plugins.git", requests[0]["source"]!!.jsonPrimitive.content)
        assertFalse("refName" in requests[0])
        assertFalse("sparsePaths" in requests[0])
        assertEquals("release", requests[1]["refName"]!!.jsonPrimitive.content)
        assertEquals("catalog/mobile", requests[1]["sparsePaths"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(
            "/data/user/0/app/no_backup/codex/mobile-marketplaces/snapshot",
            requests[2]["source"]!!.jsonPrimitive.content,
        )
        assertFalse("refName" in requests[2])
        assertFalse("sparsePaths" in requests[2])
    }

    @Test
    fun idempotentPluginMutationRetriesAfterAnAmbiguousTimeout(): Unit = runBlocking {
        var attempts = 0
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "marketplace/add" -> if (++attempts == 2) {
                    server.respond(message.id, buildJsonObject {
                        put("alreadyAdded", true)
                        put("installedRoot", "/marketplace")
                        put("marketplaceName", "plugins")
                    })
                }
            }
        }

        CodexAgentClient(
            runtimeFactory = { runtime },
            requestTimeoutMillis = 1_000,
            pluginRequestTimeoutMillis = 50,
        ).use { client ->
            client.addPluginMarketplace("/data/user/0/app/no_backup/codex/mobile-marketplaces/snapshot")
        }

        assertEquals(2, attempts)
    }

    @Test
    fun providerCodeInstallsBeforeItsPluginAndIsRemovedAfterUninstall(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val provider = object : PluginProviderHost {
            private var installed = false
            private var pending: AgentPluginReference? = null
            override suspend fun install(
                plugin: AgentPluginReference,
                mcpServerNames: Set<String>,
            ): ProviderInstallDisposition {
                events += "provider-install"
                assertEquals(setOf("drive"), mcpServerNames)
                installed = true
                pending = plugin
                return ProviderInstallDisposition.READY
            }
            override fun pendingInstalls() = listOfNotNull(pending)
            override fun installCompleted(pluginId: String) { events += "provider-complete"; pending = null }
            override fun manages(pluginId: String) = installed
            override fun mcpServerNames(pluginId: String) = setOf("drive")
            override suspend fun prepareRemoval(pluginId: String): ProviderRemovalResult {
                events += "provider-prepare"
                return ProviderRemovalResult.ready()
            }
            override suspend fun remove(pluginId: String) { events += "provider-remove"; installed = false }
        }
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/read" -> server.respond(message.id, pluginDetail(installed = false))
                "plugin/install" -> {
                    events += "plugin-install"
                    server.respond(message.id, buildJsonObject {
                        put("authPolicy", "ON_USE")
                        putJsonArray("appsNeedingAuth") {}
                    })
                }
                "config/value/write" -> { events += "config-write"; server.respond(message.id, buildJsonObject {}) }
                "plugin/uninstall" -> { events += "plugin-uninstall"; server.respond(message.id, buildJsonObject {}) }
            }
        }
        val reference = AgentPluginReference("sample@catalog", "sample", "catalog", "/marketplace")

        val result = CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000, providerHost = provider).use { client ->
            client.installPlugin(reference)
            client.uninstallPlugin(reference)
        }

        assertTrue(result.completed)
        assertEquals(
            listOf(
                "provider-install", "config-write", "config-write", "plugin-install", "provider-complete", "config-write",
                "provider-prepare", "plugin-uninstall", "provider-remove",
            ),
            events,
        )
    }

    @Test
    fun repairingProviderCodeDoesNotReinstallAnInstalledPlugin(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val writes = mutableListOf<JsonObject>()
        val provider = object : PluginProviderHost {
            override suspend fun install(plugin: AgentPluginReference, mcpServerNames: Set<String>) =
                ProviderInstallDisposition.READY.also { events += "provider-install" }
            override fun manages(pluginId: String) = true
            override fun mcpServerNames(pluginId: String) = setOf("drive")
            override fun installCompleted(pluginId: String) { events += "provider-complete" }
            override suspend fun prepareRemoval(pluginId: String) = ProviderRemovalResult.ready()
            override suspend fun remove(pluginId: String) = Unit
        }
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/read" -> server.respond(message.id, pluginDetail(installed = true))
                "config/value/write" -> {
                    events += "mcp-disable"
                    writes += message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, buildJsonObject {})
                }
                "plugin/install" -> error("Repair must not reinstall the standard plugin")
            }
        }

        CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000, providerHost = provider).use { client ->
            client.installPlugin(AgentPluginReference("sample@catalog", "sample", "catalog", "/marketplace"))
        }

        assertEquals(listOf("provider-install", "mcp-disable", "mcp-disable", "provider-complete"), events)
        assertEquals("mcp_servers.drive", writes[0]["keyPath"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, writes[0]["value"])
        assertEquals(
            "plugins.sample@catalog.mcp_servers.drive.enabled",
            writes[1]["keyPath"]!!.jsonPrimitive.content,
        )
        assertFalse(writes[1]["value"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun newProviderToolsAreAvailableWithoutRecreatingTheClient(): Unit = runBlocking {
        var providerInstalled = false
        var threadStart: JsonObject? = null
        val definition = BuiltInToolDefinition(
            pluginId = "drive@openai-curated",
            name = "drive_search",
            description = "Search Drive",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
                put("additionalProperties", false)
            },
        )
        val dispatcher = object : BuiltInToolDispatcher {
            override fun definitions() = if (providerInstalled) listOf(definition) else emptyList()
            override suspend fun execute(call: BuiltInToolCall) = BuiltInToolResult.text("unused")
        }
        val provider = object : PluginProviderHost {
            override suspend fun install(plugin: AgentPluginReference, mcpServerNames: Set<String>) =
                ProviderInstallDisposition.READY.also { providerInstalled = true }
            override fun manages(pluginId: String) = providerInstalled
            override fun mcpServerNames(pluginId: String) = setOf("drive")
            override fun installCompleted(pluginId: String) = Unit
            override suspend fun prepareRemoval(pluginId: String) = ProviderRemovalResult.ready()
            override suspend fun remove(pluginId: String) = Unit
        }
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/read" -> server.respond(message.id, pluginDetail(installed = false))
                "config/value/write" -> server.respond(message.id, buildJsonObject {})
                "plugin/install" -> server.respond(message.id, buildJsonObject {
                    put("authPolicy", "ON_USE")
                    putJsonArray("appsNeedingAuth") {}
                })
                "plugin/installed" -> server.respond(message.id, pluginList(installed = true))
                "thread/start" -> {
                    threadStart = message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, buildJsonObject {
                        putJsonObject("thread") { put("id", "thread-1") }
                    })
                }
            }
        }

        CodexAgentClient(
            runtimeFactory = { runtime },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = dispatcher,
            providerHost = provider,
        ).use { client ->
            client.installPlugin(
                AgentPluginReference(
                    "drive@openai-curated",
                    "drive",
                    "openai-curated",
                    remotePluginId = REMOTE_PLUGIN_ID,
                ),
            )
            client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
        }

        assertEquals(
            listOf("drive_search"),
            threadStart!!["dynamicTools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
    }

}
