package io.github.ciurlaro.codexmobile.agent.codex

import io.github.ciurlaro.codexmobile.core.AgentFormFieldType
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentTurnRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
    fun `encodes ordered deduplicated skill and plugin invocations`() {
        val skill = AgentInvocation.Skill("review", "/skills/review/SKILL.md")
        val plugin = AgentInvocation.Plugin("drive", "plugin://drive@openai-curated")
        val input = turnInput(
            AgentTurnRequest(
                prompt = "Check this",
                invocations = listOf(skill, plugin, skill),
            ),
        )

        assertEquals("\$review\n@drive\n\nCheck this", input[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("text", "skill", "mention"), input.map {
            it.jsonObject["type"]!!.jsonPrimitive.content
        })
        assertEquals("/skills/review/SKILL.md", input[1].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("plugin://drive@openai-curated", input[2].jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `decodes supported elicitation forms and rejects unsafe urls`() {
        val elicitation = parseElicitation(
            "7",
            buildJsonObject {
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
            },
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
        val process = ScriptedProcess { message, server ->
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
            val plugin = client.listPlugins("/workspace").plugins.single()
            assertTrue(plugin.installed)
            assertEquals("drive", client.readPlugin(plugin.reference).connectors.single().id)
            assertEquals("drive", client.installPlugin(plugin.reference).connectorsNeedingAuthentication.single().id)
            client.uninstallPlugin(plugin.reference.id)
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

    private fun skillsResponse() = buildJsonObject {
        putJsonArray("data") {
            add(buildJsonObject {
                put("cwd", "/workspace")
                putJsonArray("errors") {}
                putJsonArray("skills") {
                    add(buildJsonObject {
                        put("name", "review")
                        put("description", "Review code")
                        put("enabled", true)
                        put("path", "/skills/review/SKILL.md")
                        put("scope", "system")
                    })
                }
            })
        }
    }

    private fun pluginList(installed: Boolean) = buildJsonObject {
        putJsonArray("marketplaces") {
            add(buildJsonObject {
                put("name", "openai-curated")
                putJsonArray("plugins") { add(pluginSummary(installed)) }
            })
        }
    }

    private fun pluginSummary(installed: Boolean) = buildJsonObject {
        put("id", "drive@openai-curated")
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

    private fun pluginDetail() = buildJsonObject {
        putJsonObject("plugin") {
            put("marketplaceName", "openai-curated")
            put("summary", pluginSummary(true))
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
