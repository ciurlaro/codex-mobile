package io.github.ciurlaro.codexmobile.app

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.agent.codex.CodexAgentClient
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform
import io.github.ciurlaro.codexmobile.platform.android.AndroidProviderRegistry
import io.github.ciurlaro.codexmobile.platform.android.InstalledProvider
import io.github.ciurlaro.codexmobile.platform.android.ProviderPackageState
import io.github.ciurlaro.codexmobile.provider.api.ProviderCall
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent
import io.github.ciurlaro.codexmobile.provider.api.ProviderResult
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
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
        val marketplaceSource = File(checkNotNull(arguments.getString("marketplacePath"))).canonicalFile
        val workspace = File(checkNotNull(arguments.getString("workspacePath"))).canonicalFile
        assertTrue(marketplaceSource.isDirectory)
        assertTrue(workspace.isDirectory)

        val platform = AndroidPlatform(context)
        platform.selectWorkspace(workspace.path)
        val marketplace = File(context.noBackupFilesDir, "codex/mobile-marketplaces/provider-e2e")
        marketplace.deleteRecursively()
        assertTrue(marketplaceSource.copyRecursively(marketplace, overwrite = true))
        val client = CodexAgentClient(
            runtimeFactory = platform::createCodexRuntime,
            requestTimeoutMillis = 30_000,
            clientVersion = "provider-e2e",
            builtInToolDispatcher = platform.builtInToolDispatcher,
            providerHost = platform.providerPackages,
        )
        try {
            client.addPluginMarketplace(marketplace.path)
            val references = client.listAvailablePlugins(workspace.path, forceRefresh = true).plugins
                .map { it.reference }
                .filter { it.id in EXPECTED_PLUGIN_IDS }
            assertEquals(EXPECTED_PLUGIN_IDS, references.map(AgentPluginReference::id).toSet())

            val registry = AndroidProviderRegistry(context)
            references.forEach { reference ->
                registry.recordInstalling(installedProvider(marketplace, reference))
                val result = client.installPlugin(reference)
                assertFalse("${reference.id} unexpectedly needs a restart", result.restartRequired)
            }

            val installed = client.listInstalledPlugins(workspace.path).plugins
                .filter { it.reference.id in EXPECTED_PLUGIN_IDS }
            assertEquals(EXPECTED_PLUGIN_IDS, installed.filter { it.installed }.map { it.reference.id }.toSet())
            val verification = AndroidProviderRegistry(context)
            val verified = EXPECTED_PLUGIN_IDS.associateWith(verification::isVerified)
            assertEquals(
                "verified=$verified settings=${verification.settings()}",
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

    private fun installedProvider(marketplace: File, reference: AgentPluginReference): InstalledProvider {
        val manifest = JSONObject(
            File(marketplace, ".agents/plugins/plugins/${reference.name}/codex-mobile-addon.json").readText(),
        )
        val providerApi = manifest.getJSONObject("providerApi")
        val android = manifest.getJSONObject("android")
        val packageInfo = android.getJSONObject("package")
        val splitNames = android.getJSONArray("splitNames").strings()
        val splitFiles = context.packageManager.getApplicationInfo(context.packageName, 0).let { info ->
            info.splitNames.orEmpty().zip(info.splitSourceDirs.orEmpty().map(::File)).toMap()
        }
        val split = checkNotNull(splitFiles[splitNames.single()]) { "${splitNames.single()} is not installed" }
        return InstalledProvider(
            pluginId = manifest.getString("pluginId"),
            providerApi = providerApi.getInt("min"),
            hostVersionCode = manifest.getJSONObject("host").getInt("versionCode"),
            implementationVersion = manifest.getString("implementationVersion"),
            displayName = manifest.getString("displayName"),
            splitNames = splitNames,
            entryPoint = android.getString("entryPoint"),
            settingsEntryPoint = android.optString("settingsEntryPoint").takeIf(String::isNotBlank),
            schemaDigest = manifest.getString("schemaDigest"),
            mcpServerNames = manifest.getJSONArray("mcpServerNames").strings(),
            pluginName = reference.name,
            marketplaceName = reference.marketplaceName,
            marketplacePath = reference.marketplacePath,
            marketplaceRepository = "ciurlaro/codex-mobile-plugins",
            apkSha256 = packageInfo.getString("sha256"),
            contentSha256 = split.apkContentSha256(),
            state = ProviderPackageState.INSTALLING,
        )
    }

    private fun JSONArray.strings() = (0 until length()).map(::getString)

    private fun File.apkContentSha256(): String = ZipFile(this).use { apk ->
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = apk.entries().asSequence()
            .filterNot { it.name.isApkSignatureEntry() }
            .sortedBy { it.name }
            .toList()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        entries.forEach { entry ->
            val name = entry.name.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf(
                (name.size ushr 24).toByte(),
                (name.size ushr 16).toByte(),
                (name.size ushr 8).toByte(),
                name.size.toByte(),
            ))
            digest.update(name)
            apk.getInputStream(entry).use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun String.isApkSignatureEntry(): Boolean {
        val upper = uppercase()
        if (upper == "META-INF/MANIFEST.MF") return true
        if (!upper.startsWith("META-INF/") || '/' in upper.removePrefix("META-INF/")) return false
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")
    }

    private companion object {
        const val DOCUMENT_SENTINEL = "Codex Mobile Documents E2E 2026"
        val EXPECTED_PLUGIN_IDS = setOf("documents@codex-mobile", "telegram@codex-mobile")
        val EXPECTED_TOOLS = setOf(
            "documents_read",
            "documents_view_pages",
            "documents_edit",
            "telegram_list_chats",
            "telegram_list_messages",
            "telegram_search_messages",
            "telegram_search_contacts",
            "telegram_download_media",
            "telegram_send_text",
            "telegram_send_file",
        )
    }
}
