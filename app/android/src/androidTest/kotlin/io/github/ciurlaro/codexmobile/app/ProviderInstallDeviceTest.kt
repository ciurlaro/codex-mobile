package io.github.ciurlaro.codexmobile.app

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.agent.codex.CodexAgentClient
import io.github.ciurlaro.codexmobile.app.ui.shell.MainActivity
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform
import io.github.ciurlaro.codexmobile.provider.api.ProviderCall
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent
import io.github.ciurlaro.codexmobile.provider.api.ProviderResult
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProviderInstallDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun signedProvidersInstallThroughAppServerAndExecuteOnDevice(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Run with -e providerE2e true", arguments.getString("providerE2e") == "true")
        val marketplaceUrl = arguments.getString("marketplaceUrl") ?: MARKETPLACE_URL
        val workspace = File(checkNotNull(arguments.getString("workspacePath"))).canonicalFile
        assertTrue(workspace.isDirectory)
        assertFalse(PROVIDER_SPLIT in installedSplits())

        val platform = AndroidPlatform(context)
        platform.selectWorkspace(workspace.path)
        val client = CodexAgentClient(
            runtimeFactory = platform::createCodexRuntime,
            requestTimeoutMillis = 30_000,
            clientVersion = "provider-e2e",
            builtInToolDispatcher = platform.builtInToolDispatcher,
            providerHost = platform.providerPackages,
        )
        try {
            client.addPluginMarketplace(platform.pluginMarketplaces.snapshot(marketplaceUrl))
            val reference = client.listAvailablePlugins(workspace.path, forceRefresh = true).plugins
                .map { it.reference }
                .single { it.id == DOCUMENTS_PLUGIN_ID }

            val result = client.installPlugin(reference)
            assertFalse("${reference.id} unexpectedly needs a restart", result.restartRequired)
            assertTrue(PROVIDER_SPLIT in installedSplits())

            val installed = client.listInstalledPlugins(workspace.path).plugins
                .single { it.reference.id == DOCUMENTS_PLUGIN_ID }
            assertTrue(installed.installed)
            assertEquals(
                EXPECTED_TOOLS,
                platform.builtInToolDispatcher!!.definitions().map { it.name }.toSet(),
            )
            client.openSession(settings = AgentRuntimeSettings(workingDirectory = workspace.path))
            val runId = System.nanoTime().toString()

            val docx = File(workspace, "provider-e2e.docx").also(File::delete)
            var dispatched = false
            val created = executeDocumentTool(
                platform, workspace, "$runId-create-docx", "documents_edit",
                buildJsonObject {
                    put("path", docx.name)
                    put("create", true)
                    put("operations", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "append_paragraph")
                            put("text", DOCUMENT_SENTINEL)
                        })
                    })
                },
                beforeMutationDispatch = { dispatched = true },
            )
            assertTrue(created.success)
            assertTrue(dispatched)
            assertTrue(docx.isFile)
            val wordText = executeDocumentTool(
                platform, workspace, "$runId-read-docx", "documents_read", readArguments(docx.name),
            )
            assertTrue(wordText.success)
            assertTrue((wordText.content.single() as ProviderContent.Text).value.contains(DOCUMENT_SENTINEL))

            if (arguments.getString("wordOnly") != "true") {
                val pdf = File(workspace, "provider-e2e.pdf").also(::writePdf)
                val pdfText = executeDocumentTool(
                    platform, workspace, "$runId-read-pdf", "documents_read", readArguments(pdf.name),
                )
                assertTrue(pdfText.success)
                assertTrue((pdfText.content.single() as ProviderContent.Text).value.contains(DOCUMENT_SENTINEL))
                val rendered = executeDocumentTool(
                    platform, workspace, "$runId-render-pdf", "documents_view_pages",
                    buildJsonObject {
                        put("path", pdf.name)
                        put("pages", buildJsonArray { add(JsonPrimitive(1)) })
                    },
                )
                assertTrue(rendered.success)
                assertTrue((rendered.content.single() as ProviderContent.Image).dataUrl.startsWith("data:image/png;base64,"))
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun signedProviderUninstallStartsARecoverablePackageUpdate(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Run with -e providerUninstallE2e true", arguments.getString("providerUninstallE2e") == "true")
        val workspace = File(checkNotNull(arguments.getString("workspacePath"))).canonicalFile
        assertTrue(workspace.isDirectory)
        assertTrue(PROVIDER_SPLIT in installedSplits())

        val activity = ActivityScenario.launch(MainActivity::class.java)
        val platform = AndroidPlatform(context)
        platform.selectWorkspace(workspace.path)
        val client = CodexAgentClient(
            runtimeFactory = platform::createCodexRuntime,
            requestTimeoutMillis = 30_000,
            clientVersion = "provider-e2e",
            builtInToolDispatcher = platform.builtInToolDispatcher,
            providerHost = platform.providerPackages,
        )
        val confirmation = launch { clickPackageUpdateConfirmation() }
        try {
            val reference = client.listInstalledPlugins(workspace.path).plugins
                .single { it.reference.id == DOCUMENTS_PLUGIN_ID }
                .reference
            val result = client.uninstallPlugin(reference)
            assertTrue(result.completed)
            assertFalse(result.restartRequired)
            assertFalse(PROVIDER_SPLIT in installedSplits())
            assertFalse(platform.providerSettings().any { it.pluginId == DOCUMENTS_PLUGIN_ID })
            activity.onActivity { assertFalse(it.isFinishing) }
        } finally {
            confirmation.cancel()
            client.close()
            activity.close()
        }
    }

    @Test
    fun signedProviderRemovalIsReconciledAfterRestart(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Run with -e providerRemovalVerifyE2e true", arguments.getString("providerRemovalVerifyE2e") == "true")
        val workspace = File(checkNotNull(arguments.getString("workspacePath"))).canonicalFile
        assertTrue(workspace.isDirectory)
        assertFalse(PROVIDER_SPLIT in installedSplits())

        val platform = AndroidPlatform(context)
        platform.selectWorkspace(workspace.path)
        assertFalse(platform.providerSettings().any { it.pluginId == DOCUMENTS_PLUGIN_ID })
        val client = CodexAgentClient(
            runtimeFactory = platform::createCodexRuntime,
            requestTimeoutMillis = 30_000,
            clientVersion = "provider-e2e",
            builtInToolDispatcher = platform.builtInToolDispatcher,
            providerHost = platform.providerPackages,
        )
        try {
            assertFalse(
                client.listInstalledPlugins(workspace.path).plugins.any { it.reference.id == DOCUMENTS_PLUGIN_ID },
            )
        } finally {
            client.close()
        }
    }

    private suspend fun executeDocumentTool(
        platform: AndroidPlatform,
        workspace: File,
        callId: String,
        tool: String,
        arguments: kotlinx.serialization.json.JsonObject,
        beforeMutationDispatch: () -> Unit = {},
    ): ProviderResult = platform.builtInToolDispatcher!!.execute(
        ProviderCall(
            threadId = "provider-e2e",
            turnId = "provider-e2e",
            callId = callId,
            pluginId = "documents@codex-mobile",
            tool = tool,
            arguments = arguments,
            workspace = workspace.path,
            argumentsHash = "0".repeat(64),
        ),
        checkActive = {},
        beforeMutationDispatch = beforeMutationDispatch,
    )

    private fun readArguments(path: String) = buildJsonObject {
        put("path", path)
        put("mode", "native")
        put("request", "text")
        put("maxChars", 20_000)
    }

    private fun writePdf(file: File) {
        file.delete()
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create())
            page.canvas.drawText(DOCUMENT_SENTINEL, 72f, 96f, Paint().apply { textSize = 20f })
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun installedSplits(): Set<String> = context.packageManager
        .getApplicationInfo(context.packageName, 0)
        .splitNames
        .orEmpty()
        .toSet()

    private suspend fun clickPackageUpdateConfirmation() {
        repeat(150) {
            val update = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("Update")
                ?.firstOrNull { it.text?.toString() == "Update" && it.isClickable }
            if (update?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            delay(200)
        }
        error("Android package update confirmation did not appear")
    }

    private companion object {
        const val DOCUMENT_SENTINEL = "Codex Mobile Documents E2E 2026"
        const val MARKETPLACE_URL = "https://github.com/ciurlaro/codex-mobile-plugins"
        const val DOCUMENTS_PLUGIN_ID = "documents@codex-mobile"
        const val PROVIDER_SPLIT = "provider_documents"
        val EXPECTED_TOOLS = setOf(
            "documents_read",
            "documents_view_pages",
            "documents_edit",
        )
    }
}
