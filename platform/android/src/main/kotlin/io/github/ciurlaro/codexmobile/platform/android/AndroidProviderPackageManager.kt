package io.github.ciurlaro.codexmobile.platform.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.ciurlaro.codexmobile.agent.codex.PluginProviderHost
import io.github.ciurlaro.codexmobile.agent.codex.ProviderInstallDisposition
import io.github.ciurlaro.codexmobile.provider.api.ProviderContext
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalState
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        val packageInfo = readDescriptor(plugin) ?: return ProviderInstallDisposition.NOT_REQUIRED
        val descriptor = packageInfo.descriptor
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
        val candidate = descriptor.toInstalledProvider(plugin, PROVIDER_API, packageInfo.marketplaceRepository)
        val previous = registry.installedRecord(plugin.id)
        val splitName = descriptor.splitNames.single()
        if (installedSplits().contains(splitName) && previous?.apkSha256 == descriptor.sha256) {
            registry.recordInstalling(candidate.copy(contentSha256 = previous.contentSha256))
            val verificationError = runCatching { registry.requireVerified(plugin.id) }.exceptionOrNull()
            if (verificationError == null) return ProviderInstallDisposition.READY
            registry.restoreInstallRecord(plugin.id, previous)
            throw descriptor.verificationFailure(verificationError)
        }
        requireInstallerPermission()

        val apk = download(descriptor)
        val contentSha256 = apk.apkContentSha256()
        try {
            registry.recordInstalling(candidate.copy(contentSha256 = contentSha256))
            installSplit(splitName, apk)
            runCatching { registry.requireVerified(plugin.id) }
                .getOrElse { throw descriptor.verificationFailure(it) }
        } catch (error: Exception) {
            val installedContent = runCatching { installedSplitFiles()[splitName]?.apkContentSha256() }.getOrNull()
            if (installedContent != contentSha256) registry.restoreInstallRecord(plugin.id, previous)
            throw error
        } finally {
            apk.delete()
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
            registry.markRemovalPrepared(pluginId, "Unverified provider is ready for code removal")
            return ProviderRemovalResult.ready("Unverified provider is ready for code removal")
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

    override suspend fun remove(pluginId: String) {
        val record = registry.installedRecord(pluginId) ?: return
        registry.markSplitRemovalPending(pluginId)
        try {
            record.splitNames.filter { it in installedSplits() }.forEach { removeSplit(it) }
        } catch (error: Exception) {
            registry.markSplitRemovalPending(pluginId, error.message ?: "Provider code removal needs retry")
            throw error
        }
    }

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
            ProviderSourcePolicy.requireProviderUri(uri, redirect > 0)
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
            if (Build.VERSION.SDK_INT >= 34) setDontKillApp(true)
        }
        commit(installer.createSession(params)) { session ->
            session.openWrite(splitName, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
        }
    }

    private suspend fun removeSplit(splitName: String) {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_INHERIT_EXISTING).apply {
            setAppPackageName(appContext.packageName)
            removeSplit(splitName)
        }
        commit(installer.createSession(params)) {}
    }

    private suspend fun commit(sessionId: Int, write: (PackageInstaller.Session) -> Unit) {
        val token = UUID.randomUUID().toString()
        val result = CompletableDeferred<Result<Unit>>()
        ProviderPackageCallbacks.register(token, result)
        try {
            installer.openSession(sessionId).use { session ->
                write(session)
                val callback = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    Intent(appContext, ProviderPackageResultReceiver::class.java)
                        .putExtra(ProviderPackageResultReceiver.EXTRA_TOKEN, token),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            try {
                withTimeout(PACKAGE_TIMEOUT_MILLIS) { result.await() }.getOrThrow()
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IllegalStateException("Android provider installation timed out")
            }
        } finally {
            ProviderPackageCallbacks.remove(token)
            runCatching { installer.abandonSession(sessionId) }
        }
    }

    private fun installedSplits(): Set<String> = installedSplitFiles().keys

    private fun installedSplitFiles(): Map<String, File> = appContext.packageManager
        .getApplicationInfo(appContext.packageName, 0)
        .let { info -> info.splitNames.orEmpty().zip(info.splitSourceDirs.orEmpty().map(::File)).toMap() }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }

    private companion object {
        const val PROVIDER_API = 2
        const val MAX_REDIRECTS = 5
        const val MAX_DESCRIPTOR_BYTES = 64L * 1024
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        const val NETWORK_TIMEOUT_MILLIS = 30_000
        const val PACKAGE_TIMEOUT_MILLIS = 15L * 60 * 1_000
    }
}

class ProviderPackageResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = pendingUserAction(intent)
                if (confirmation == null) {
                    finish(context, token, Result.failure(IllegalStateException("Provider installation needs confirmation")))
                } else {
                    runCatching {
                        context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { finish(context, token, Result.failure(it)) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                finish(context, token, Result.success(Unit))
            }
            else -> finish(context, token, Result.failure(IllegalStateException(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Provider package update failed",
            )))
        }
    }

    private fun finish(context: Context, token: String, result: Result<Unit>) {
        if (!ProviderPackageCallbacks.complete(token, result)) {
            if (result.isSuccess) ProviderInstallRestart.mark(context)
            notifyAfterRestart(context, result)
        }
    }

    @Suppress("DEPRECATION")
    private fun pendingUserAction(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }

    private fun notifyAfterRestart(context: Context, result: Result<Unit>) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Provider installation", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        notifications.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(if (result.isSuccess) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle("Codex Mobile")
                .setContentText(if (result.isSuccess) {
                    "Provider installed. Open Codex Mobile to finish setup."
                } else {
                    result.exceptionOrNull()?.message ?: "Provider installation failed"
                })
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val EXTRA_TOKEN = "providerPackageToken"
        private const val CHANNEL_ID = "provider-installation"
        private const val NOTIFICATION_ID = 5002
    }
}

internal object ProviderPackageCallbacks {
    private val callbacks = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()

    fun register(token: String, result: CompletableDeferred<Result<Unit>>) {
        check(callbacks.putIfAbsent(token, result) == null)
    }

    fun complete(token: String, result: Result<Unit>): Boolean = callbacks.remove(token)?.complete(result) == true

    fun remove(token: String) {
        callbacks.remove(token)
    }
}

internal object ProviderInstallRestart {
    private const val PREFERENCES = "provider-installation"
    private const val PENDING = "resume"

    fun mark(context: Context) {
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(PENDING, true).commit())
    }

    @Synchronized
    fun consume(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(PENDING, false)) return false
        check(preferences.edit().remove(PENDING).commit())
        return true
    }
}

private fun ProviderPackageDescriptor.verificationFailure(cause: Throwable) = IllegalStateException(
    "$displayName provider verification failed: ${cause.message ?: "the installed code does not match its metadata"}",
    cause,
)

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
        splitNames = splitNames,
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

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun File.apkContentSha256(): String = ZipFile(this).use { apk ->
    val digest = MessageDigest.getInstance("SHA-256")
    val entries = apk.entries().asSequence()
        .filterNot { it.name.isApkSignatureEntry() }
        .sortedBy { it.name }
        .toList()
    require(entries.map { it.name }.distinct().size == entries.size) { "Provider APK contains duplicate entries" }
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
    digest.digest().toHex()
}

private fun String.isApkSignatureEntry(): Boolean {
    val upper = uppercase()
    if (upper == "META-INF/MANIFEST.MF") return true
    if (!upper.startsWith("META-INF/") || '/' in upper.removePrefix("META-INF/")) return false
    return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")
}

@Suppress("DEPRECATION")
internal fun android.content.pm.PackageInfo.compatVersionCode(): Int =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode.toInt() else versionCode

private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
internal const val CANONICAL_PROVIDER_REPOSITORY = "ciurlaro/codex-mobile-plugins"
