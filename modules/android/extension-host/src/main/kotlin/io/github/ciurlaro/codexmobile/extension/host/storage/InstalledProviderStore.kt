package io.github.ciurlaro.codexmobile.extension.host

import android.content.Context
import android.util.AtomicFile
import io.github.ciurlaro.codexmobile.agent.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.BuiltInToolDispatcher
import io.github.ciurlaro.codexmobile.agent.BuiltInToolResult
import io.github.ciurlaro.codexmobile.provider.api.CodexMobileProvider
import io.github.ciurlaro.codexmobile.provider.api.ProviderContext
import io.github.ciurlaro.codexmobile.provider.api.ProviderWorkspace
import io.github.ciurlaro.codexmobile.platform.android.DocumentsProvider
import io.github.ciurlaro.codexmobile.platform.android.TelegramProvider
import io.github.ciurlaro.codexmobile.providers.documents.DOCUMENTS_PLUGIN_ID
import io.github.ciurlaro.codexmobile.providers.telegram.TELEGRAM_PLUGIN_ID
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal fun InstalledProvider.pluginReference() = AgentPluginReference(
    pluginId,
    pluginName,
    marketplaceName,
    marketplacePath,
)

internal class InstalledProviderStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "providers/installed.json"))
    private var corrupt = false

    @Synchronized
    fun read(): List<InstalledProvider> {
        if (corrupt) return emptyList()
        if (!file.baseFile.isFile) return emptyList()
        return try {
            file.openRead().bufferedReader().use { reader ->
                val array = JSONArray(reader.readText())
                (0 until array.length()).map { index -> array.getJSONObject(index).installedProvider() }
            }
        } catch (_: Exception) {
            corrupt = true
            emptyList()
        }
    }

    @Synchronized
    fun write(providers: List<InstalledProvider>) {
        check(!corrupt) { "Provider authority state is unreadable" }
        file.baseFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val bytes = JSONArray().apply {
            providers.sortedBy(InstalledProvider::pluginId).forEach { put(it.json()) }
        }.toString().toByteArray()
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}

private fun JSONObject.installedProvider() = InstalledProvider(
    pluginId = getString("pluginId"),
    providerApi = getInt("providerApi"),
    hostVersionCode = getInt("hostVersionCode"),
    implementationVersion = getString("implementationVersion"),
    displayName = getString("displayName"),
    splitNames = getJSONArray("splitNames").let { values -> (0 until values.length()).map(values::getString) },
    entryPoint = getString("entryPoint"),
    settingsEntryPoint = optString("settingsEntryPoint").takeIf(String::isNotEmpty),
    schemaDigest = getString("schemaDigest"),
    mcpServerNames = getJSONArray("mcpServerNames").let { values ->
        (0 until values.length()).map(values::getString)
    },
    pluginName = getString("pluginName"),
    marketplaceName = getString("marketplaceName"),
    marketplacePath = optString("marketplacePath").takeIf(String::isNotEmpty),
    marketplaceRepository = optString("marketplaceRepository").takeIf(String::isNotEmpty),
    apkSha256 = optString("apkSha256"),
    contentSha256 = optString("contentSha256").takeIf(String::isNotEmpty),
    state = ProviderPackageState.valueOf(getString("state")),
    message = optString("message").takeIf(String::isNotEmpty),
    delivery = optString("delivery").takeIf(String::isNotEmpty)?.let(ProviderDelivery::valueOf)
        ?: ProviderDelivery.LEGACY_SPLIT,
)

private fun InstalledProvider.json() = JSONObject()
    .put("pluginId", pluginId)
    .put("providerApi", providerApi)
    .put("hostVersionCode", hostVersionCode)
    .put("implementationVersion", implementationVersion)
    .put("displayName", displayName)
    .put("splitNames", JSONArray(splitNames))
    .put("entryPoint", entryPoint)
    .put("settingsEntryPoint", settingsEntryPoint)
    .put("schemaDigest", schemaDigest)
    .put("mcpServerNames", JSONArray(mcpServerNames))
    .put("pluginName", pluginName)
    .put("marketplaceName", marketplaceName)
    .put("marketplacePath", marketplacePath)
    .put("marketplaceRepository", marketplaceRepository)
    .put("apkSha256", apkSha256)
    .put("contentSha256", contentSha256)
    .put("state", state.name)
    .put("message", message)
    .put("delivery", delivery.name)

internal val BUNDLED_PROVIDER_FACTORIES: Map<String, (Context) -> CodexMobileProvider> = mapOf(
    DOCUMENTS_PLUGIN_ID to ::DocumentsProvider,
    TELEGRAM_PLUGIN_ID to ::TelegramProvider,
)
