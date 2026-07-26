package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.PluginListResponse
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.McpServerElicitationRequestParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.UserInputMentionUserInput
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.UserInputSkillUserInput
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.UserInputTextUserInput
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalResult

import io.github.ciurlaro.codexmobile.core.AgentFormFieldType
import io.github.ciurlaro.codexmobile.core.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
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

class SkillsPluginsProtocolTest {
    @Test
    fun `github marketplace source uses app server marketplace method without assuming a branch`(): Unit = runBlocking {
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
    fun `idempotent plugin mutation retries after an ambiguous timeout`(): Unit = runBlocking {
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
    fun `provider code installs before its plugin and is removed after uninstall`(): Unit = runBlocking {
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
        val reference = AgentPluginReference("sample@catalog", "sample", "catalog")

        val result = CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000, providerHost = provider).use { client ->
            client.installPlugin(reference)
            client.uninstallPlugin(reference)
        }

        assertTrue(result.completed)
        assertFalse(result.restartRequired)
        assertEquals(
            listOf(
                "provider-install", "config-write", "config-write", "plugin-install", "provider-complete", "config-write",
                "provider-prepare", "plugin-uninstall", "provider-remove",
            ),
            events,
        )
    }

    @Test
    fun `repairing provider code does not reinstall an installed plugin`(): Unit = runBlocking {
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
            client.installPlugin(AgentPluginReference("sample@catalog", "sample", "catalog"))
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
    fun `new provider tools are available without recreating the client`(): Unit = runBlocking {
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
            client.installPlugin(AgentPluginReference("drive@openai-curated", "drive", "openai-curated"))
            client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
        }

        assertEquals(
            listOf("drive_search"),
            threadStart!!["dynamicTools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `retryable provider cleanup keeps its plugin and code installed but disabled`(): Unit = runBlocking {
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
            client.uninstallPlugin(AgentPluginReference("sample@catalog", "sample", "catalog"))
        }

        assertFalse(result.completed)
        assertEquals("Remote revocation could not be confirmed", result.message)
        assertEquals(listOf("plugin-disable", "provider-prepare"), events)
    }

    @Test
    fun `prepared provider removal resumes after restart without delaying installed plugins`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        val removed = CountDownLatch(1)
        val reference = AgentPluginReference("drive@openai-curated", "drive", "openai-curated")
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
    fun `pending provider install completes immediately after restart authentication`(): Unit = runBlocking {
        val reference = AgentPluginReference("drive@openai-curated", "drive", "openai-curated")
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
    fun `encodes ordered deduplicated skill and plugin invocations`() {
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
    fun `decodes supported elicitation forms and rejects unsafe urls`() {
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
    fun `uses pinned app server capability endpoints`(): Unit = runBlocking {
        val methods = mutableListOf<String>()
        var skillWrite: Boolean? = null
        var pluginWrite: String? = null
        val process = FakeCodexRuntime { message, server ->
            message.method?.let(methods::add)
            when (message.method) {
                "initialize" -> {
                    val capabilities = message.objectValue["params"]!!.jsonObject["capabilities"]!!.jsonObject
                    assertTrue(capabilities["experimentalApi"]!!.jsonPrimitive.content.toBoolean())
                    assertFalse(capabilities["mcpServerOpenaiFormElicitation"]!!.jsonPrimitive.content.toBoolean())
                    server.respond(message.id, buildJsonObject {})
                }
                "skills/list" -> server.respond(message.id, skillsResponse())
                "skills/config/write" -> {
                    skillWrite = message.objectValue["params"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean()
                    server.respond(message.id, buildJsonObject { put("effectiveEnabled", true) })
                }
                "plugin/list" -> server.respond(message.id, pluginList(installed = false))
                "plugin/installed" -> server.respond(message.id, pluginList(installed = true))
                "plugin/read" -> server.respond(message.id, pluginDetail())
                "plugin/install" -> server.respond(
                    message.id,
                    buildJsonObject {
                        put("authPolicy", "ON_INSTALL")
                        putJsonArray("appsNeedingAuth") { add(connector()) }
                    },
                )
                "plugin/uninstall" -> server.respond(message.id, buildJsonObject {})
                "config/value/write" -> {
                    pluginWrite = message.objectValue["params"]!!.jsonObject["keyPath"]!!.jsonPrimitive.content
                    server.respond(message.id, buildJsonObject {})
                }
                "app/list" -> server.respond(
                    message.id,
                    buildJsonObject { putJsonArray("data") { add(connector()) } },
                )
                "mcpServerStatus/list" -> server.respond(
                    message.id,
                    buildJsonObject {
                        putJsonArray("data") {
                            add(buildJsonObject {
                                put("name", "codex_apps")
                                put("authStatus", "oAuth")
                            })
                            add(buildJsonObject {
                                put("name", "drive")
                                put("authStatus", "notLoggedIn")
                            })
                        }
                    },
                )
                "mcpServer/oauth/login" -> server.respond(
                    message.id,
                    buildJsonObject { put("authorizationUrl", "https://accounts.example.com/oauth") },
                )
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            assertEquals("review", client.listSkills("/workspace").skills.single().name)
            client.setSkillEnabled("/skills/review/SKILL.md", true)
            val plugin = client.listInstalledPlugins("/workspace").plugins.single()
            assertFalse(client.listAvailablePlugins("/workspace").plugins.single().installed)
            assertTrue(plugin.installed)
            assertEquals("drive", client.readPlugin(plugin.reference).connectors.single().id)
            assertEquals("drive", client.installPlugin(plugin.reference).connectorsNeedingAuthentication.single().id)
            client.uninstallPlugin(plugin.reference)
            client.setPluginEnabled(plugin.reference.id, true)
            assertTrue(client.listConnectors().single().isAccessible)
            assertEquals("drive", client.listMcpServers().single().name)
            assertEquals("https://accounts.example.com/oauth", client.startMcpOauth("drive"))
            assertEquals(true, skillWrite)
            assertEquals("plugins.drive@openai-curated.enabled", pluginWrite)
            listOf("skills/list", "plugin/list", "plugin/installed", "plugin/read", "plugin/install", "app/list")
                .forEach { assertTrue(it in methods) }
        } finally {
            client.close()
        }
    }

    @Test
    fun `reads long skill source without splitting utf8 characters`(): Unit = runBlocking {
        val source = File.createTempFile("codex-skill-", ".md")
        val expected = "a".repeat(32 * 1024 - 1) + "€" + "tail"
        source.writeText(expected)
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "skills/list" -> server.respond(message.id, skillsResponse(source.absolutePath))
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            client.listSkills("/workspace")
            val actual = buildString {
                var offset: Long? = 0
                while (offset != null) {
                    val chunk = client.readSkill(source.absolutePath, offset)
                    append(chunk.content)
                    offset = chunk.nextOffset
                }
            }
            assertEquals(expected, actual)
        } finally {
            client.close()
            source.delete()
        }
    }

    @Test
    fun `available plugin discovery serves cache and keeps stale data after refresh failure`(): Unit = runBlocking {
        val cache = Files.createTempDirectory("plugin-cache-").toFile()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/list" -> server.respond(message.id, pluginList(installed = false))
            }
        }
        CodexAgentClient({ process }, requestTimeoutMillis = 1_000, pluginCacheDirectory = cache).use { client ->
            assertEquals(AgentCatalogFreshness.LIVE, client.listAvailablePlugins("/workspace").freshness)
        }

        val cached = CodexAgentClient(
            runtimeFactory = { error("Network should not be used for cached discovery") },
            requestTimeoutMillis = 100,
            pluginCacheDirectory = cache,
        )
        try {
            assertEquals(AgentCatalogFreshness.FRESH_CACHE, cached.listAvailablePlugins("/workspace").freshness)
            assertTrue(cache.listFiles().orEmpty().single().setLastModified(0))
            assertEquals(AgentCatalogFreshness.STALE_CACHE, cached.listAvailablePlugins("/workspace").freshness)
            val fallback = cached.listAvailablePlugins("/workspace", forceRefresh = true)
            assertEquals(AgentCatalogFreshness.STALE_CACHE, fallback.freshness)
            assertTrue(fallback.plugins.isNotEmpty())
            assertTrue(fallback.errors.isNotEmpty())
        } finally {
            cached.close()
            cache.deleteRecursively()
        }
    }

    @Test
    fun `available plugin discovery does not retry an empty catalog`(): Unit = runBlocking {
        var requests = 0
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/list" -> {
                    requests++
                    server.respond(
                        message.id,
                        if (requests == 1) emptyPluginList() else pluginList(installed = false),
                    )
                }
            }
        }

        CodexAgentClient({ process }, requestTimeoutMillis = 1_000).use { client ->
            assertTrue(client.listAvailablePlugins("/workspace").plugins.isEmpty())
            assertEquals(1, requests)
        }
    }

    @Test
    fun `installed plugins ignore marketplace refresh failures`(): Unit = runBlocking {
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> server.respond(
                    message.id,
                    buildJsonObject {
                        putJsonArray("marketplaces") {
                            add(buildJsonObject {
                                put("name", "openai-curated")
                                putJsonArray("plugins") { add(pluginSummary(installed = true)) }
                            })
                        }
                        putJsonArray("marketplaceLoadErrors") {
                            add(buildJsonObject { put("message", "Stream Closed") })
                        }
                    },
                )
            }
        }

        CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000).use { client ->
            val catalog = client.listInstalledPlugins("/workspace")
            assertTrue(catalog.plugins.single().installed)
            assertTrue(catalog.errors.isEmpty())
        }
    }

    @Test
    fun `cancelled plugin refresh does not surface cached data as an error result`(): Unit = runBlocking {
        val cache = Files.createTempDirectory("plugin-cache-").toFile()
        val refreshStarted = CompletableDeferred<Unit>()
        var requests = 0
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/list" -> if (++requests == 1) {
                    server.respond(message.id, pluginList(installed = false))
                } else {
                    refreshStarted.complete(Unit)
                }
            }
        }

        CodexAgentClient({ runtime }, requestTimeoutMillis = 5_000, pluginCacheDirectory = cache).use { client ->
            client.listAvailablePlugins("/workspace", forceRefresh = true)
            var delivered = false
            val refresh = launch {
                client.listAvailablePlugins("/workspace", forceRefresh = true)
                delivered = true
            }
            refreshStarted.await()
            refresh.cancel()
            refresh.join()
            assertFalse(delivered)
        }
        cache.deleteRecursively()
    }

    @Test
    fun `plugin discovery accepts marketplaces exposed by app server`() {
        val response = Json.decodeFromJsonElement(
            PluginListResponse.serializer(),
            pluginList(installed = false, marketplace = "team-catalog"),
        )
        val plugins = parsePluginMarketplaces(response.marketplaces)

        assertEquals("team-catalog", plugins.single().reference.marketplaceName)
    }

    @Test
    fun `plugin read sends exactly one marketplace identity`(): Unit = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/read" -> {
                    requests += message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, pluginDetail())
                }
            }
        }

        CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000).use { client ->
            client.readPlugin(AgentPluginReference("local@catalog", "local", "catalog", "/marketplace"))
            client.readPlugin(AgentPluginReference("remote@catalog", "remote", "catalog"))
        }

        assertEquals(setOf("pluginName", "marketplacePath"), requests[0].keys)
        assertEquals(setOf("pluginName", "remoteMarketplaceName"), requests[1].keys)
    }

    @Test
    fun `maps a stale remote plugin entry to unavailable`(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/install" -> server.sendRaw(
                    buildJsonObject {
                        put("id", message.id)
                        putJsonObject("error") {
                            put("code", -32600)
                            put("message", "remote plugin request failed with status 404: Plugin not found")
                        }
                    }.toString(),
                )
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val error = runCatching {
                client.installPlugin(AgentPluginReference("missing@remote", "missing", "remote"))
            }.exceptionOrNull()

            assertEquals("missing@remote", assertIs<AgentPluginUnavailableException>(error).pluginId)
        } finally {
            client.close()
        }
    }

    private fun skillsResponse(path: String = "/skills/review/SKILL.md") = buildJsonObject {
        putJsonArray("data") {
            add(buildJsonObject {
                put("cwd", "/workspace")
                putJsonArray("errors") {}
                putJsonArray("skills") {
                    add(buildJsonObject {
                        put("name", "review")
                        put("description", "Review code")
                        put("enabled", true)
                        put("path", path)
                        put("scope", "system")
                    })
                }
            })
        }
    }

    private fun emptyPluginList() = buildJsonObject {
        putJsonArray("marketplaces") {}
        putJsonArray("marketplaceLoadErrors") {}
    }

    private fun pluginList(installed: Boolean, marketplace: String = "openai-curated") = buildJsonObject {
        putJsonArray("marketplaces") {
            add(buildJsonObject {
                put("name", marketplace)
                putJsonArray("plugins") { add(pluginSummary(installed, marketplace)) }
            })
        }
    }

    private fun pluginSummary(installed: Boolean, marketplace: String = "openai-curated") = buildJsonObject {
        put("id", "drive@$marketplace")
        put("name", "drive")
        put("installed", installed)
        put("enabled", true)
        put("installPolicy", "AVAILABLE")
        put("authPolicy", "ON_INSTALL")
        put("availability", "AVAILABLE")
        putJsonObject("source") { put("type", "remote") }
        putJsonObject("interface") {
            put("displayName", "Drive")
            put("shortDescription", "Files in Drive")
            put("capabilities", buildJsonArray { add(JsonPrimitive("Search files")) })
            put("screenshotUrls", buildJsonArray {})
            put("screenshots", buildJsonArray {})
        }
    }

    private fun pluginDetail(installed: Boolean = true) = buildJsonObject {
        putJsonObject("plugin") {
            put("marketplaceName", "openai-curated")
            put("summary", pluginSummary(installed))
            putJsonArray("skills") {}
            putJsonArray("apps") { add(connector()) }
            putJsonArray("appTemplates") {}
            putJsonArray("mcpServers") { add(JsonPrimitive("drive")) }
            putJsonArray("hooks") {}
        }
    }

    private fun connector() = buildJsonObject {
        put("id", "drive")
        put("name", "Drive")
        put("description", "Files")
        put("installUrl", "https://accounts.example.com/oauth")
        put("isAccessible", true)
        put("isEnabled", true)
    }
}
