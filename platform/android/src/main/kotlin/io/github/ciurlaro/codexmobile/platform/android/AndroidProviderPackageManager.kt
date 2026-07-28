package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.os.Build
import io.github.ciurlaro.codexmobile.agent.codex.PluginProviderHost
import io.github.ciurlaro.codexmobile.agent.codex.ProviderInstallDisposition
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.provider.api.ProviderContext
import io.github.ciurlaro.codexmobile.provider.api.ProviderDescriptor
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalState
import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AndroidProviderPackageManager(
    context: Context,
    private val registry: AndroidProviderRegistry,
) : PluginProviderHost {
    private val appContext = context.applicationContext

    override suspend fun install(
        plugin: AgentPluginReference,
        mcpServerNames: Set<String>,
    ): ProviderInstallDisposition {
        val packageInfo = readDescriptor(plugin) ?: return ProviderInstallDisposition.NOT_REQUIRED
        val descriptor = packageInfo.descriptor
        val provider = registry.bundledProvider(plugin.id) ?: throw AgentPluginUnavailableException(
            plugin.id,
            descriptor.displayName,
            "${descriptor.displayName} requires a newer Codex Mobile version",
        )
        val hostVersion = appContext.packageManager.getPackageInfo(appContext.packageName, 0).compatVersionCode()
        validateBundledProvider(descriptor, provider.descriptor, provider.javaClass.name, mcpServerNames, hostVersion)

        val previous = registry.installedRecord(plugin.id)
        try {
            registry.recordInstalling(
                descriptor.toInstalledProvider(plugin, provider.descriptor.providerApi, packageInfo.marketplaceRepository),
            )
            registry.requireVerified(plugin.id)
        } catch (error: Exception) {
            registry.restoreInstallRecord(plugin.id, previous)
            throw IllegalStateException(
                "${descriptor.displayName} provider verification failed: " +
                    (error.message ?: "the bundled code does not match its metadata"),
                error,
            )
        }
        return ProviderInstallDisposition.READY
    }

    override fun manages(pluginId: String): Boolean = registry.installedRecord(pluginId) != null

    override fun mcpServerNames(pluginId: String): Set<String> = registry.mcpServerNames(pluginId)

    override fun pendingInstalls(): List<AgentPluginReference> = registry.pendingInstalls()

    override fun preparedRemovals(): List<AgentPluginReference> = registry.preparedRemovals()

    override fun installCompleted(pluginId: String) = registry.installCompleted(pluginId)

    override suspend fun prepareRemoval(pluginId: String): ProviderRemovalResult {
        registry.installedRecord(pluginId) ?: return ProviderRemovalResult.ready()
        registry.markRemovalPending(pluginId, "Provider cleanup was interrupted; retry removal")
        val provider = registry.provider(pluginId)
        if (provider == null) {
            BuiltInMutationJournal(appContext).use { it.compact(pluginId) }
            registry.secretStore(pluginId).clear()
            registry.markRemovalPrepared(pluginId, "Provider state is ready for removal")
            return ProviderRemovalResult.ready()
        }
        val result = provider.prepareUninstall(ProviderContext({}, registry.secretStore(pluginId).snapshot()))
        if (result.state == ProviderRemovalState.READY) {
            BuiltInMutationJournal(appContext).use { it.compact(pluginId) }
            registry.secretStore(pluginId).clear()
            registry.markRemovalPrepared(pluginId, result.message)
        } else {
            registry.markRemovalPending(pluginId, result.message)
        }
        return result
    }

    override suspend fun remove(pluginId: String) = registry.remove(pluginId)

    private fun readDescriptor(plugin: AgentPluginReference): ProviderPackageInfo? {
        val codexRoot = File(appContext.noBackupFilesDir, "codex").canonicalFile
        val marketplace = plugin.marketplacePath?.let(::File)
        val roots = buildList {
            marketplace?.let { add(if (it.isFile) it.parentFile else it) }
            val installed = File(appContext.noBackupFilesDir, "codex/plugins/cache/${plugin.marketplaceName}/${plugin.name}")
            installed.listFiles()?.filter(File::isDirectory)?.sortedByDescending(File::lastModified)?.let(::addAll)
        }.map { root ->
            root.canonicalFile.also {
                require(it.toPath().startsWith(codexRoot.toPath())) { "Provider marketplace path is outside app storage" }
            }
        }
        val manifest = roots.asSequence().mapNotNull { root ->
            sequenceOf(
                File(root, "plugins/${plugin.name}/codex-mobile-addon.json"),
                File(root, "${plugin.name}/codex-mobile-addon.json"),
                File(root, "codex-mobile-addon.json"),
            ).firstOrNull(File::isFile)
        }.firstOrNull() ?: return null
        require(manifest.length() in 1..MAX_DESCRIPTOR_BYTES) { "Provider add-on metadata has an invalid size" }
        val repository = ProviderSourcePolicy.marketplaceRepository(manifest, codexRoot)
        ProviderSourcePolicy.requireCanonicalRepository(repository)
        return ProviderPackageInfo(ProviderPackageDescriptor.parse(manifest.readText()), repository)
    }

    private companion object {
        const val MAX_DESCRIPTOR_BYTES = 64L * 1024
    }
}
