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

internal fun validateBundledProvider(
    addOn: ProviderPackageDescriptor,
    bundled: ProviderDescriptor,
    bundledEntryPoint: String,
    mcpServerNames: Set<String>,
    hostVersion: Int,
    supportedAbis: Set<String> = Build.SUPPORTED_ABIS.toSet(),
) {
    check(addOn.pluginId == bundled.pluginId) { "Provider plugin ID does not match its bundled code" }
    check(addOn.mcpServerNames.toSet() == mcpServerNames) {
        "Provider MCP configuration does not match its add-on metadata"
    }
    check(bundled.providerApi in addOn.minProviderApi..addOn.maxProviderApi) {
        "This provider requires an unsupported host API"
    }
    check(addOn.hostVersionCode == hostVersion && hostVersion in bundled.minHostVersionCode..bundled.maxHostVersionCode) {
        "This provider was built for another Codex Mobile version"
    }
    check(supportedAbis.any(addOn.abis::contains)) { "This provider does not support this device ABI" }
    check(addOn.implementationVersion == bundled.implementationVersion)
    check(addOn.displayName == bundled.displayName)
    check(addOn.schemaDigest == bundled.schemaDigest)
    check(addOn.entryPoint == bundledEntryPoint)
    check(addOn.settingsEntryPoint == bundled.settingsEntryPoint)
}

internal data class ProviderPackageInfo(
    val descriptor: ProviderPackageDescriptor,
    val marketplaceRepository: String,
)

internal data class ProviderPackageDescriptor(
    val minProviderApi: Int,
    val maxProviderApi: Int,
    val hostVersionCode: Int,
    val pluginId: String,
    val implementationVersion: String,
    val displayName: String,
    val schemaDigest: String,
    val mcpServerNames: List<String>,
    val splitNames: List<String>,
    val entryPoint: String,
    val settingsEntryPoint: String?,
    val abis: List<String>,
    val apkUri: URI,
    val sha256: String,
) {
    fun toInstalledProvider(
        plugin: AgentPluginReference,
        providerApi: Int,
        marketplaceRepository: String,
    ) = InstalledProvider(
        pluginId = pluginId,
        providerApi = providerApi,
        hostVersionCode = hostVersionCode,
        implementationVersion = implementationVersion,
        displayName = displayName,
        delivery = ProviderDelivery.BUNDLED,
        splitNames = emptyList(),
        entryPoint = entryPoint,
        settingsEntryPoint = settingsEntryPoint,
        schemaDigest = schemaDigest,
        mcpServerNames = mcpServerNames,
        pluginName = plugin.name,
        marketplaceName = plugin.marketplaceName,
        marketplacePath = plugin.marketplacePath,
        marketplaceRepository = marketplaceRepository,
        apkSha256 = sha256,
        contentSha256 = null,
        state = ProviderPackageState.INSTALLING,
    )

    companion object {
        fun parse(value: String): ProviderPackageDescriptor {
            val root = Json.parseToJsonElement(value).jsonObject
            root.requireOnly(
                "formatVersion", "providerApi", "host", "pluginId", "implementationVersion",
                "displayName", "schemaDigest", "mcpServerNames", "android",
            )
            check(root.getValue("formatVersion").jsonPrimitive.int == 1) { "Unsupported provider manifest" }
            val providerApi = root.getValue("providerApi").jsonObject
            val host = root.getValue("host").jsonObject
            val android = root.getValue("android").jsonObject
            val packageInfo = android.getValue("package").jsonObject
            providerApi.requireOnly("min", "max")
            host.requireOnly("versionCode")
            android.requireOnly("splitNames", "entryPoint", "settingsEntryPoint", "abis", "package")
            packageInfo.requireOnly("url", "sha256")
            val checksum = packageInfo.getValue("sha256").jsonPrimitive.content.lowercase()
            require(checksum.matches(Regex("[a-f0-9]{64}"))) { "Invalid provider APK checksum" }
            val schemaDigest = root.getValue("schemaDigest").jsonPrimitive.content.lowercase()
            require(schemaDigest.matches(Regex("[a-f0-9]{64}"))) { "Invalid provider schema digest" }
            return ProviderPackageDescriptor(
                minProviderApi = providerApi.getValue("min").jsonPrimitive.int,
                maxProviderApi = providerApi.getValue("max").jsonPrimitive.int,
                hostVersionCode = host.getValue("versionCode").jsonPrimitive.int,
                pluginId = root.getValue("pluginId").jsonPrimitive.content,
                implementationVersion = root.getValue("implementationVersion").jsonPrimitive.content,
                displayName = root.getValue("displayName").jsonPrimitive.content,
                schemaDigest = schemaDigest,
                mcpServerNames = root.getValue("mcpServerNames").jsonArray.map { it.jsonPrimitive.content },
                splitNames = android.getValue("splitNames").jsonArray.map { it.jsonPrimitive.content },
                entryPoint = android.getValue("entryPoint").jsonPrimitive.content,
                settingsEntryPoint = android["settingsEntryPoint"]?.jsonPrimitive?.content,
                abis = android.getValue("abis").jsonArray.map { it.jsonPrimitive.content },
                apkUri = URI(packageInfo.getValue("url").jsonPrimitive.content),
                sha256 = checksum,
            ).also {
                require(it.minProviderApi > 0 && it.maxProviderApi >= it.minProviderApi) { "Invalid provider API range" }
                require(it.hostVersionCode > 0) { "Invalid host version" }
                require(it.pluginId.matches(Regex("[a-z0-9-]+@[a-z0-9-]+"))) { "Invalid provider plugin ID" }
                require(it.displayName.isNotBlank()) { "Invalid provider display name" }
                require(it.splitNames.size == 1 && it.splitNames.all { split -> split.matches(Regex("[a-z][a-z0-9_]{0,79}")) }) {
                    "Invalid provider split names"
                }
                require(it.entryPoint.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]{1,299}"))) { "Invalid provider entry point" }
                require(it.mcpServerNames.isNotEmpty() && it.mcpServerNames.distinct().size == it.mcpServerNames.size &&
                    it.mcpServerNames.all { name -> name.matches(Regex("[A-Za-z0-9_-]{1,64}")) }) {
                    "Invalid provider MCP server names"
                }
                require(it.settingsEntryPoint == null || it.settingsEntryPoint.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]{1,299}"))) {
                    "Invalid provider settings entry point"
                }
                require(it.abis.isNotEmpty() && it.abis.all { abi -> abi in SUPPORTED_ABIS }) { "Invalid provider ABIs" }
                ProviderSourcePolicy.requireProviderUri(it.apkUri, redirected = false)
            }
        }
    }
}

internal object ProviderSourcePolicy {
    fun requireCanonicalRepository(repository: String) {
        require(repository == CANONICAL_PROVIDER_REPOSITORY) {
            "Android providers must come from $CANONICAL_PROVIDER_REPOSITORY"
        }
    }

    fun marketplaceRepository(manifest: File, codexRoot: File): String {
        val boundary = codexRoot.canonicalFile
        val source = manifest.canonicalFile
        require(source.toPath().startsWith(boundary.toPath())) { "Provider manifest is outside app storage" }
        var directory: File? = source.parentFile
        while (directory != null && directory.toPath().startsWith(boundary.toPath())) {
            val config = File(directory, ".git/config")
            if (config.isFile) {
                require(config.length() in 1..MAX_GIT_CONFIG_BYTES) { "Provider marketplace Git metadata is invalid" }
                return normalizeGitHubRepository(originUrl(config.readLines()))
            }
            if (directory == boundary) break
            directory = directory.parentFile
        }
        error("Provider marketplace origin is unavailable")
    }

    fun requireProviderUri(uri: URI, redirected: Boolean) {
        require(uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.fragment == null) {
            "Provider APK URL must be HTTPS"
        }
        val host = uri.host?.lowercase().orEmpty()
        if (!redirected || host == "github.com") {
            require(host == "github.com" && uri.query == null &&
                uri.rawPath.startsWith("/$CANONICAL_PROVIDER_REPOSITORY/releases/download/") &&
                !uri.rawPath.contains("%2f", ignoreCase = true)) {
                "Provider APK must be a release from $CANONICAL_PROVIDER_REPOSITORY"
            }
        } else {
            require(host in GITHUB_RELEASE_ASSET_HOSTS) { "Provider APK redirect is not a GitHub release asset" }
        }
    }

    internal fun normalizeGitHubRepository(value: String): String {
        val trimmed = value.trim()
        val path = when {
            SCP_GITHUB.matches(trimmed) -> SCP_GITHUB.matchEntire(trimmed)!!.groupValues[1]
            else -> {
                val uri = URI(trimmed)
                require(uri.host.equals("github.com", ignoreCase = true) && uri.query == null && uri.fragment == null) {
                    "Provider marketplace origin must be GitHub"
                }
                require(uri.scheme.equals("https", ignoreCase = true) ||
                    uri.scheme.equals("ssh", ignoreCase = true) && uri.userInfo == "git") {
                    "Provider marketplace origin has an unsupported protocol"
                }
                uri.path.trim('/')
            }
        }.removeSuffix(".git")
        val segments = path.split('/')
        require(segments.size == 2 && segments.all { it.matches(GITHUB_NAME) }) {
            "Provider marketplace origin is not a repository"
        }
        return segments.joinToString("/") { it.lowercase() }
    }

    private fun originUrl(lines: List<String>): String {
        var origin = false
        lines.forEach { line ->
            val value = line.trim()
            if (value.startsWith('[')) {
                origin = value.equals("[remote \"origin\"]", ignoreCase = true)
            } else if (origin && value.substringBefore('=', "").trim().equals("url", ignoreCase = true)) {
                return value.substringAfter('=').trim().removeSurrounding("\"")
            }
        }
        error("Provider marketplace origin is unavailable")
    }

    private const val MAX_GIT_CONFIG_BYTES = 64L * 1024
    private val SCP_GITHUB = Regex("git@github\\.com:([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?)")
    private val GITHUB_NAME = Regex("[A-Za-z0-9_.-]+")
    private val GITHUB_RELEASE_ASSET_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
}

private fun kotlinx.serialization.json.JsonObject.requireOnly(vararg names: String) {
    require(keys.all { it in names }) { "Provider manifest contains an unsupported field" }
}

@Suppress("DEPRECATION")
internal fun android.content.pm.PackageInfo.compatVersionCode(): Int =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode.toInt() else versionCode

private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
internal const val CANONICAL_PROVIDER_REPOSITORY = "ciurlaro/codex-mobile-plugins"
