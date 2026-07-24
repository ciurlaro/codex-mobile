package io.github.ciurlaro.codexmobile.agent.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProviderSecretsTest {
    @Test
    fun `dispatcher gives a provider only its runtime secret namespace`(): Unit = runBlocking {
        val definition = BuiltInToolDefinition(
            pluginId = "example@catalog",
            name = "example_read",
            description = "Read an example.",
            inputSchema = buildJsonObject { put("type", "object"); put("additionalProperties", false) },
        )
        val provider = object : CodexMobileProvider {
            override val descriptor = ProviderDescriptor(
                pluginId = definition.pluginId,
                implementationVersion = "1",
                tools = listOf(definition),
                secrets = listOf(ProviderSecretDefinition("token", "Access token")),
            )

            override suspend fun execute(call: ProviderCall, context: ProviderContext) =
                BuiltInToolResult.text(checkNotNull(context.secrets.get("token")))
        }
        val dispatcher = ProviderToolDispatcher(listOf(provider)) { pluginId ->
            ProviderSecrets { name -> if (pluginId == definition.pluginId && name == "token") "scoped" else null }
        }

        val result = dispatcher.execute(
            BuiltInToolCall("thread", "turn", "call", definition.pluginId, definition.name, buildJsonObject {}, "/", "hash"),
        )

        assertEquals("scoped", (result.content.single() as BuiltInToolContent.Text).value)
    }

    @Test
    fun `provider descriptors reject duplicate secret names`() {
        val definition = BuiltInToolDefinition(
            "example@catalog",
            "example_read",
            "Read an example.",
            buildJsonObject { put("type", "object") },
        )
        assertFailsWith<IllegalArgumentException> {
            ProviderDescriptor(
                definition.pluginId,
                "1",
                listOf(definition),
                secrets = listOf(
                    ProviderSecretDefinition("token", "First token"),
                    ProviderSecretDefinition("token", "Second token"),
                ),
            )
        }
    }
}
