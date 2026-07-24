package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.util.AtomicFile
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolDispatcher
import io.github.ciurlaro.codexmobile.agent.codex.CodexMobileProvider
import io.github.ciurlaro.codexmobile.agent.codex.ProviderToolDispatcher
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class ProviderPackageState { INSTALLING, ACTIVE, REMOVAL_PENDING, REMOVAL_PREPARED, SPLIT_REMOVAL_PENDING }

data class InstalledProvider(
    val pluginId: String,
    val providerApi: Int,
    val hostVersionCode: Int,
    val implementationVersion: String,
    val displayName: String,
    val splitNames: List<String>,
    val entryPoint: String,
    val settingsEntryPoint: String?,
    val schemaDigest: String,
    val mcpServerNames: List<String>,
    val pluginName: String,
    val marketplaceName: String,
    val marketplacePath: String?,
    val state: ProviderPackageState,
    val message: String? = null,
)

data class ProviderSettingsEntry(
    val pluginId: String,
    val displayName: String,
    val activityClassName: String?,
    val removalNeedsRetry: Boolean,
    val message: String?,
)

class AndroidProviderRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val store = InstalledProviderStore(appContext)

    init {
        reconcileRemovedSplits()
    }

    val dispatcher: BuiltInToolDispatcher
        get() = ProviderToolDispatcher(verifiedProviders()) { pluginId -> secretStore(pluginId).snapshot() }

    fun secretStore(pluginId: String) = AndroidProviderSecretStore(appContext, pluginId)

    fun provider(pluginId: String): CodexMobileProvider? = verifiedProviders().singleOrNull {
        it.descriptor.pluginId == pluginId
    }

    fun settings(): List<ProviderSettingsEntry> = store.read().mapNotNull { record ->
        if (record.state == ProviderPackageState.REMOVAL_PREPARED) return@mapNotNull null
        if (record.state == ProviderPackageState.SPLIT_REMOVAL_PENDING) {
            return@mapNotNull ProviderSettingsEntry(
                pluginId = record.pluginId,
                displayName = record.displayName,
                activityClassName = null,
                removalNeedsRetry = true,
                message = record.message ?: "Provider code removal needs retry",
            )
        }
        val entryPoint = record.settingsEntryPoint ?: return@mapNotNull null
        val provider = verifiedProvider(record) ?: return@mapNotNull null
        val secretMessage = runCatching {
            val secrets = secretStore(record.pluginId).snapshot()
            if (provider.descriptor.secrets.any { secrets.get(it.name) == null }) "Configuration required" else null
        }.getOrElse { "Provider secrets are unreadable" }
        ProviderSettingsEntry(
            pluginId = record.pluginId,
            displayName = record.displayName,
            activityClassName = entryPoint,
            removalNeedsRetry = record.state == ProviderPackageState.REMOVAL_PENDING,
            message = record.message ?: secretMessage,
        )
    }

    fun installedRecords(): List<InstalledProvider> = store.read()

    fun installedRecord(pluginId: String): InstalledProvider? = store.read().singleOrNull { it.pluginId == pluginId }

    fun mcpServerNames(pluginId: String): Set<String> = installedRecord(pluginId)?.mcpServerNames.orEmpty().toSet()

    fun pendingInstalls(): List<AgentPluginReference> = store.read()
        .filter { it.state == ProviderPackageState.INSTALLING && verifiedProvider(it) != null }
        .map(InstalledProvider::pluginReference)

    fun preparedRemovals(): List<AgentPluginReference> = store.read()
        .filter { it.state == ProviderPackageState.REMOVAL_PREPARED }
        .map(InstalledProvider::pluginReference)

    fun installCompleted(pluginId: String) = update(pluginId) { it.copy(state = ProviderPackageState.ACTIVE, message = null) }

    fun recordInstalling(provider: InstalledProvider) = store.write(
        store.read().filterNot { it.pluginId == provider.pluginId } + provider.copy(state = ProviderPackageState.INSTALLING),
    )

    fun restoreInstallRecord(pluginId: String, previous: InstalledProvider?) = store.write(
        store.read().filterNot { it.pluginId == pluginId } + listOfNotNull(previous),
    )

    fun markRemovalPending(pluginId: String, message: String? = null) = update(pluginId) {
        it.copy(state = ProviderPackageState.REMOVAL_PENDING, message = message)
    }

    fun markRemovalPrepared(pluginId: String, message: String? = null) = update(pluginId) {
        it.copy(state = ProviderPackageState.REMOVAL_PREPARED, message = message)
    }

    fun markSplitRemovalPending(
        pluginId: String,
        message: String = "Restarting to finish provider removal",
    ) = update(pluginId) {
        it.copy(state = ProviderPackageState.SPLIT_REMOVAL_PENDING, message = message)
    }

    fun isVerified(pluginId: String): Boolean = installedRecord(pluginId)?.let(::verifiedProvider) != null

    fun reconcileRemovedSplits() {
        val installed = installedSplits()
        val records = store.read()
        val reconciled = records.filterNot { record ->
            record.state == ProviderPackageState.SPLIT_REMOVAL_PENDING && record.splitNames.none(installed::contains)
        }
        if (reconciled != records) store.write(reconciled)
    }

    private fun verifiedProviders(): List<CodexMobileProvider> = store.read()
        .filter { it.state != ProviderPackageState.SPLIT_REMOVAL_PENDING }
        .mapNotNull(::verifiedProvider)

    private fun verifiedProvider(record: InstalledProvider): CodexMobileProvider? = runCatching {
        check(splitsInstalled(record)) { "Provider split is missing" }
        val provider = Class.forName(record.entryPoint)
            .getConstructor(Context::class.java)
            .newInstance(appContext) as CodexMobileProvider
        val descriptor = provider.descriptor
        val currentVersion = appContext.packageManager.getPackageInfo(appContext.packageName, 0).compatVersionCode()
        check(record.hostVersionCode == currentVersion && currentVersion in descriptor.minHostVersionCode..descriptor.maxHostVersionCode)
        check(descriptor.providerApi == record.providerApi)
        check(descriptor.pluginId == record.pluginId)
        check(descriptor.implementationVersion == record.implementationVersion)
        check(descriptor.displayName == record.displayName)
        check(descriptor.settingsEntryPoint == record.settingsEntryPoint)
        check(descriptor.schemaDigest == record.schemaDigest)
        provider
    }.getOrNull()

    private fun splitsInstalled(record: InstalledProvider): Boolean = installedSplits().containsAll(record.splitNames)

    private fun installedSplits(): Set<String> = appContext.packageManager
        .getApplicationInfo(appContext.packageName, 0).splitNames.orEmpty().toSet()

    private fun update(pluginId: String, transform: (InstalledProvider) -> InstalledProvider) {
        val records = store.read()
        check(records.count { it.pluginId == pluginId } == 1) { "Provider lifecycle record is missing" }
        store.write(records.map { if (it.pluginId == pluginId) transform(it) else it })
    }
}

private fun InstalledProvider.pluginReference() = AgentPluginReference(
    pluginId,
    pluginName,
    marketplaceName,
    marketplacePath,
)

private class InstalledProviderStore(context: Context) {
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
    state = ProviderPackageState.valueOf(getString("state")),
    message = optString("message").takeIf(String::isNotEmpty),
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
    .put("state", state.name)
    .put("message", message)
