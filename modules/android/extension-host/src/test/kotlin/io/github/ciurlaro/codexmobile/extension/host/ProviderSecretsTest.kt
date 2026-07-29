package io.github.ciurlaro.codexmobile.extension.host

import io.github.ciurlaro.codexmobile.agent.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent
import io.github.ciurlaro.codexmobile.provider.api.CodexMobileProvider
import io.github.ciurlaro.codexmobile.provider.api.ProviderCall
import io.github.ciurlaro.codexmobile.provider.api.ProviderContext
import io.github.ciurlaro.codexmobile.provider.api.ProviderDescriptor
import io.github.ciurlaro.codexmobile.provider.api.ProviderSecretDefinition
import io.github.ciurlaro.codexmobile.provider.api.ProviderSecrets
import java.util.concurrent.atomic.AtomicInteger
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
                schemaDigest = providerSchemaDigest(listOf(definition)),
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

        assertEquals("scoped", (result.content.single() as ProviderContent.Text).value)
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
                schemaDigest = providerSchemaDigest(listOf(definition)),
                secrets = listOf(
                    ProviderSecretDefinition("token", "First token"),
                    ProviderSecretDefinition("token", "Second token"),
                ),
            )
        }
    }

    @Test
    fun `dispatcher exposes the host deadline and active check without consuming mutation authority`(): Unit = runBlocking {
        val definition = BuiltInToolDefinition(
            "example@catalog",
            "example_read",
            "Read an example.",
            buildJsonObject { put("type", "object") },
        )
        val provider = object : CodexMobileProvider {
            override val descriptor = ProviderDescriptor(
                definition.pluginId,
                "1",
                listOf(definition),
                schemaDigest = providerSchemaDigest(listOf(definition)),
            )

            override suspend fun execute(call: ProviderCall, context: ProviderContext): BuiltInToolResult {
                context.ensureActive()
                return BuiltInToolResult.text(context.deadlineEpochMillis.toString())
            }
        }
        val checks = AtomicInteger()
        val dispatcher = ProviderToolDispatcher(listOf(provider))
        val call = BuiltInToolCall(
            "thread", "turn", "call", definition.pluginId, definition.name,
            buildJsonObject {}, "/", "hash", deadlineEpochMillis = 123L,
        )

        val result = dispatcher.execute(call, checkActive = { checks.incrementAndGet() }, beforeMutationDispatch = {})

        assertEquals(2, checks.get())
        assertEquals("123", (result.content.single() as ProviderContent.Text).value)
    }
}
