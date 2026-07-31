package io.github.ciurlaro.codexmobile.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.CodexAgentClient
import io.github.ciurlaro.codexmobile.app.composition.AndroidPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

internal class PluginLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun livePluginCatalogsComeFromThePackagedAppServer(): Unit = runBlocking {
        assumeTrue(
            "Run physical plugin discovery with -e pluginE2e true",
            InstrumentationRegistry.getArguments().getString("pluginE2e") == "true",
        )
        val platform = AndroidPlatform(context)
        val workspace = requireNotNull(platform.activeWorkspacePath())
        CodexAgentClient(platform::createCodexRuntime, 30_000).use { client ->
            requirePersistedAuthentication(client)
            val installed = client.listInstalledPlugins(workspace, forceRefresh = true)
            val available = client.listAvailablePlugins(workspace, forceRefresh = true)
            val connectorPlugin = available.plugins.firstOrNull { it.reference.name == "slack" }
                ?: error("The official Slack connector plugin is unavailable")
            val connectorDetail = client.readPlugin(connectorPlugin.reference)

            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "installedCatalog",
                        "${installed.freshness}; count=${installed.plugins.size}; " +
                            "errors=${installed.errors}; names=${installed.plugins.names()}",
                    )
                    putString(
                        "availableCatalog",
                        "${available.freshness}; count=${available.plugins.size}; " +
                            "errors=${available.errors}; names=${available.plugins.names()}",
                    )
                    putString(
                        "connectorAuthBoundary",
                        "${connectorPlugin.authPolicy}; connectors=" +
                            connectorDetail.connectors.joinToString { it.id },
                    )
                },
            )

            assertEquals(AgentCatalogFreshness.LIVE, installed.freshness)
            assertTrue(installed.errors.toString(), installed.errors.isEmpty())
            assertEquals(AgentCatalogFreshness.LIVE, available.freshness)
            assertTrue(available.errors.toString(), available.errors.isEmpty())
            assertTrue("The live plugin marketplace is empty", available.plugins.isNotEmpty())
            assertTrue("Slack exposes no connector authentication boundary", connectorDetail.connectors.isNotEmpty())
        }
    }

    @Test
    fun connectorFreeOfficialPluginCompletesItsLocalLifecycle(): Unit = runBlocking {
        assumeTrue(
            "Run reversible plugin mutation with -e pluginLifecycleE2e true",
            InstrumentationRegistry.getArguments().getString("pluginLifecycleE2e") == "true",
        )
        val platform = AndroidPlatform(context)
        val workspace = requireNotNull(platform.activeWorkspacePath())
        CodexAgentClient(platform::createCodexRuntime, 30_000).use { client ->
            requirePersistedAuthentication(client)
            val available = client.listAvailablePlugins(workspace, forceRefresh = true)
            val candidate = listOf("build-macos-apps", "remotion")
                .firstNotNullOfOrNull { name ->
                    available.plugins.firstOrNull {
                        it.reference.name == name && !it.installed &&
                            it.installPolicy == AgentPluginInstallPolicy.AVAILABLE
                    }
                }
                ?: error("A connector-free official test plugin is not available")
            assertTrue(client.readPlugin(candidate.reference).connectors.isEmpty())

            var installedByTest = false
            try {
                val result = client.installPlugin(candidate.reference)
                installedByTest = true
                assertTrue(result.connectorsNeedingAuthentication.isEmpty())
                val installed = client.plugin(workspace, candidate.reference.id)
                assertTrue(installed.installed)
                assertTrue(installed.enabled)
                val installedEndpoint = client.listInstalledPlugins(workspace, forceRefresh = true)
                assertEquals(AgentCatalogFreshness.LIVE, installedEndpoint.freshness)
                assertTrue(installedEndpoint.errors.toString(), installedEndpoint.errors.isEmpty())
            } finally {
                val stillInstalled = runCatching {
                    client.plugin(workspace, candidate.reference.id).installed
                }.getOrDefault(false)
                if (installedByTest || stillInstalled) {
                    assertTrue(client.uninstallTestPlugin(candidate.reference))
                }
            }
            assertFalse(client.plugin(workspace, candidate.reference.id).installed)
            instrumentation.sendStatus(
                2,
                Bundle().apply { putString("pluginLifecycle", "${candidate.reference.id}: restored uninstalled") },
            )
        }
    }

    private suspend fun requirePersistedAuthentication(client: CodexAgentClient) = coroutineScope {
        val event = async { withTimeout(30_000) { client.events.first() } }
        client.authenticate()
        assertTrue("Persisted ChatGPT authentication is required", event.await() === AgentEvent.Authenticated)
    }

    private fun List<io.github.ciurlaro.codexmobile.agent.AgentPluginSummary>.names(): String =
        take(20).joinToString { "${it.reference.id}:${it.installed}:${it.installPolicy}" }

    private suspend fun CodexAgentClient.plugin(workspace: String, id: String) =
        listAvailablePlugins(workspace, forceRefresh = true).plugins.single { it.reference.id == id }

    private suspend fun CodexAgentClient.uninstallTestPlugin(plugin: AgentPluginReference): Boolean {
        var lastFailure: Exception? = null
        repeat(3) { attempt ->
            try {
                return uninstallPlugin(plugin).completed
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailure = error
                if (attempt < 2) delay((attempt + 1) * 1_000L)
            }
        }
        throw requireNotNull(lastFailure)
    }
}
