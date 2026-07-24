package io.github.ciurlaro.codexmobile.platform.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.ciurlaro.codexmobile.agent.codex.PluginProviderHost
import io.github.ciurlaro.codexmobile.agent.codex.ProviderInstallDisposition
import io.github.ciurlaro.codexmobile.agent.codex.ProviderRemovalResult
import io.github.ciurlaro.codexmobile.agent.codex.ProviderRemovalState
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private val installer get() = appContext.packageManager.packageInstaller

    override suspend fun install(
        plugin: AgentPluginReference,
        mcpServerNames: Set<String>,
    ): ProviderInstallDisposition {
        val descriptor = readDescriptor(plugin) ?: return ProviderInstallDisposition.NOT_REQUIRED
        check(descriptor.pluginId == plugin.id) { "Provider plugin ID does not match its plugin" }
        check(descriptor.mcpServerNames.toSet() == mcpServerNames) {
            "Provider MCP configuration does not match its add-on metadata"
        }
        check(PROVIDER_API in descriptor.minProviderApi..descriptor.maxProviderApi) {
            "This provider requires an unsupported host API"
        }
        val hostVersion = appContext.packageManager.getPackageInfo(appContext.packageName, 0).compatVersionCode()
        check(descriptor.hostVersionCode == hostVersion) { "This provider was built for another Codex Mobile version" }
        check(Build.SUPPORTED_ABIS.any(descriptor.abis::contains)) { "This provider does not support this device ABI" }
        val candidate = descriptor.toInstalledProvider(plugin)
        val previous = registry.installedRecord(plugin.id)
        if (installedSplits().containsAll(descriptor.splitNames)) {
            registry.recordInstalling(candidate)
            if (registry.isVerified(plugin.id)) return ProviderInstallDisposition.READY
            registry.restoreInstallRecord(plugin.id, previous)
        }
        requireInstallerPermission()

        val apk = download(descriptor)
        try {
            verifyArchive(descriptor, apk)
            registry.recordInstalling(candidate)
            installSplit(descriptor.splitNames.single(), apk)
        } catch (error: Throwable) {
            registry.restoreInstallRecord(plugin.id, previous)
            throw error
        } finally {
            apk.delete()
        }
        return ProviderInstallDisposition.RESTART_REQUIRED
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
            ?: return ProviderRemovalResult.retry("Provider verification failed; removal was not started")
        val result = provider.prepareUninstall()
        if (result.state == ProviderRemovalState.READY) {
            BuiltInMutationJournal(appContext).use { it.compact(pluginId) }
            registry.markRemovalPrepared(pluginId, result.message)
        } else {
            registry.markRemovalPending(pluginId, result.message)
        }
        return result
    }

    override suspend fun remove(pluginId: String) {
        val record = registry.installedRecord(pluginId) ?: return
        registry.markSplitRemovalPending(pluginId)
        try {
            record.splitNames.filter { it in installedSplits() }.forEach { removeSplit(it) }
        } catch (error: Throwable) {
            registry.markSplitRemovalPending(pluginId, error.message ?: "Provider code removal needs retry")
            throw error
        }
    }

    private fun readDescriptor(plugin: AgentPluginReference): ProviderPackageDescriptor? {
        val marketplace = plugin.marketplacePath?.let(::File)
        val roots = buildList {
            marketplace?.let { add(if (it.isFile) it.parentFile else it) }
            val installed = File(appContext.noBackupFilesDir, "codex/plugins/cache/${plugin.marketplaceName}/${plugin.name}")
            installed.listFiles()?.filter(File::isDirectory)?.sortedByDescending(File::lastModified)?.let(::addAll)
        }
        val manifest = roots.asSequence().mapNotNull { root ->
            sequenceOf(
                File(root, "plugins/${plugin.name}/codex-mobile-addon.json"),
                File(root, "${plugin.name}/codex-mobile-addon.json"),
                File(root, "codex-mobile-addon.json"),
            ).firstOrNull(File::isFile)
        }.firstOrNull() ?: return null
        require(manifest.length() in 1..MAX_DESCRIPTOR_BYTES) { "Provider add-on metadata has an invalid size" }
        return ProviderPackageDescriptor.parse(manifest.readText())
    }

    private fun requireInstallerPermission() {
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            appContext.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            error("Allow Codex Mobile to install provider add-ons, then retry")
        }
    }

    private suspend fun download(descriptor: ProviderPackageDescriptor): File = withContext(Dispatchers.IO) {
        val destination = File(appContext.cacheDir, "provider-${UUID.randomUUID()}.apk")
        var uri = descriptor.apkUri
        repeat(MAX_REDIRECTS + 1) { redirect ->
            requireProviderUri(uri)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
            connection.connect()
            connection.use {
                if (it.responseCode in 300..399) {
                    check(redirect < MAX_REDIRECTS) { "Too many provider download redirects" }
                    uri = uri.resolve(checkNotNull(it.getHeaderField("Location")) { "Provider redirect has no location" })
                    return@repeat
                }
                check(it.responseCode in 200..299) { "Provider download returned HTTP ${it.responseCode}" }
                val expectedSize = it.contentLengthLong
                check(expectedSize in 1..MAX_APK_BYTES) { "Provider APK has an invalid size" }
                val digest = MessageDigest.getInstance("SHA-256")
                it.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= MAX_APK_BYTES) { "Provider APK exceeds the size limit" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                check(digest.digest().toHex() == descriptor.sha256) { "Provider APK checksum does not match" }
                return@withContext destination
            }
        }
        error("Provider download failed")
    }

    private suspend fun installSplit(splitName: String, apk: File) {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_INHERIT_EXISTING).apply {
            setAppPackageName(appContext.packageName)
        }
        commit(installer.createSession(params)) { session ->
            session.openWrite(splitName, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
        }
    }

    private fun verifyArchive(descriptor: ProviderPackageDescriptor, apk: File) {
        val archive = packageArchiveInfo(apk)
            ?: error("Provider APK is unreadable")
        val installed = packageInfo()
        check(archive.packageName == appContext.packageName) { "Provider APK package name does not match" }
        check(archive.compatVersionCode() == descriptor.hostVersionCode) { "Provider APK version does not match" }
        check(archive.splitNames.orEmpty().singleOrNull() in descriptor.splitNames) {
            "Provider APK split name does not match"
        }
        fun certificates(info: android.content.pm.PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) {
            checkNotNull(info.signingInfo) { "Package signing information is missing" }.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") checkNotNull(info.signatures) { "Package signing information is missing" }
        })
            .map { signer -> MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).toHex() }
            .toSet()
        check(certificates(archive) == certificates(installed)) { "Provider APK signing certificate does not match" }
    }

    @Suppress("DEPRECATION")
    private fun packageArchiveInfo(apk: File) = if (Build.VERSION.SDK_INT >= 33) {
        appContext.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        appContext.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo() = if (Build.VERSION.SDK_INT >= 33) {
        appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        appContext.packageManager.getPackageInfo(
            appContext.packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        )
    }

    private suspend fun removeSplit(splitName: String) {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_INHERIT_EXISTING).apply {
            setAppPackageName(appContext.packageName)
            removeSplit(splitName)
        }
        commit(installer.createSession(params)) {}
    }

    private suspend fun commit(sessionId: Int, write: (PackageInstaller.Session) -> Unit) {
        val action = "${appContext.packageName}.PROVIDER_PACKAGE.${UUID.randomUUID()}"
        val result = CompletableDeferred<Result<Unit>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> pendingUserAction(intent)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(it)
                    } ?: result.complete(Result.failure(IllegalStateException("Provider installation needs confirmation")))
                    PackageInstaller.STATUS_SUCCESS -> result.complete(Result.success(Unit))
                    else -> result.complete(Result.failure(IllegalStateException(
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Provider package update failed",
                    )))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") appContext.registerReceiver(receiver, IntentFilter(action))
        }
        try {
            installer.openSession(sessionId).use { session ->
                write(session)
                val callback = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    Intent(action).setPackage(appContext.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            withTimeout(PACKAGE_TIMEOUT_MILLIS) { result.await() }.getOrThrow()
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
        }
    }

    @Suppress("DEPRECATION")
    private fun pendingUserAction(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }

    private fun installedSplits(): Set<String> = appContext.packageManager
        .getApplicationInfo(appContext.packageName, 0).splitNames.orEmpty().toSet()

    private fun requireProviderUri(uri: URI) {
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null) {
            "Provider APK URL must be HTTPS"
        }
        val host = uri.host?.lowercase().orEmpty()
        require(host == "github.com" || host.endsWith(".githubusercontent.com")) {
            "Provider APK must be hosted by GitHub"
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }

    private companion object {
        const val PROVIDER_API = 1
        const val MAX_REDIRECTS = 5
        const val MAX_DESCRIPTOR_BYTES = 64L * 1024
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        const val NETWORK_TIMEOUT_MILLIS = 30_000
        const val PACKAGE_TIMEOUT_MILLIS = 120_000L
    }
}

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
    fun toInstalledProvider(plugin: AgentPluginReference) = InstalledProvider(
        pluginId = pluginId,
        providerApi = 1,
        hostVersionCode = hostVersionCode,
        implementationVersion = implementationVersion,
        displayName = displayName,
        splitNames = splitNames,
        entryPoint = entryPoint,
        settingsEntryPoint = settingsEntryPoint,
        schemaDigest = schemaDigest,
        mcpServerNames = mcpServerNames,
        pluginName = plugin.name,
        marketplaceName = plugin.marketplaceName,
        marketplacePath = plugin.marketplacePath,
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
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.requireOnly(vararg names: String) {
    require(keys.all { it in names }) { "Provider manifest contains an unsupported field" }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

@Suppress("DEPRECATION")
internal fun android.content.pm.PackageInfo.compatVersionCode(): Int =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode.toInt() else versionCode

private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
