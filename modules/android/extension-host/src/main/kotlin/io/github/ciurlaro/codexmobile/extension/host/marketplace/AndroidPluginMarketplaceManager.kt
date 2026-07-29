package io.github.ciurlaro.codexmobile.extension.host

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject

class AndroidPluginMarketplaceManager internal constructor(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "codex/mobile-marketplaces")
    private val mutationMutex = Mutex()

    suspend fun snapshot(sourceUrl: String): String = prepareSnapshot(sourceUrl, reuseExisting = false)

    suspend fun snapshotOrReuse(sourceUrl: String): String = prepareSnapshot(sourceUrl, reuseExisting = true)

    private suspend fun prepareSnapshot(sourceUrl: String, reuseExisting: Boolean): String = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val source = GitHubMarketplaceLocation.parse(sourceUrl)
            if (reuseExisting) {
                findReusableMarketplaceSnapshot(root, source)?.let { return@withLock it.canonicalPath }
            }
            val ref = source.ref ?: readJson(
                "https://api.github.com/repos/${source.repository.owner}/${source.repository.repo}",
            ).getString("default_branch").also {
                require(isGitHubSegment(it)) { "The repository default branch is not supported" }
            }
            val destination = File(root, source.snapshotName(ref))

            check(root.isDirectory || root.mkdirs()) { "Unable to prepare plugin marketplace storage" }
            val staging = File(root, ".install-${UUID.randomUUID()}")
            check(staging.mkdirs()) { "Unable to prepare plugin marketplace snapshot" }
            try {
                val encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8.name()).replace("+", "%20")
                openConnection(
                    "https://codeload.github.com/${source.repository.owner}/${source.repository.repo}/zip/$encodedRef",
                ).useResponse { response ->
                    check(response.responseCode in 200..299) { "GitHub returned HTTP ${response.responseCode}" }
                    ZipInputStream(LimitedInputStream(response.inputStream, MAX_DOWNLOAD_BYTES).buffered()).use { zip ->
                        extractMarketplaceArchive(zip, source.path, staging)
                    }
                }
                check(hasMarketplaceManifest(staging)) { "The repository has no valid plugin marketplace" }
                writeOrigin(staging, source.repository)
                writeSource(staging, source)
                check(isValidMarketplaceSnapshot(staging)) { "The marketplace contains an invalid local plugin path" }
                replaceMarketplaceSnapshot(staging, destination)
                destination.canonicalPath
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }
    }

    fun marketplaceName(snapshotPath: String): String = readMarketplaceName(File(snapshotPath))

    private fun readJson(url: String): JSONObject = openConnection(url).apply {
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    }.useResponse { response ->
        check(response.responseCode in 200..299) { "GitHub returned HTTP ${response.responseCode}" }
        JSONObject(LimitedInputStream(response.inputStream, MAX_METADATA_BYTES).bufferedReader().use { it.readText() })
    }

    private fun hasMarketplaceManifest(directory: File): Boolean =
        File(directory, ".agents/plugins/marketplace.json").let { it.isFile && it.length() in 1..MAX_MANIFEST_BYTES }

    private fun writeOrigin(directory: File, repository: GitHubRepository) {
        val git = File(directory, ".git")
        check(git.isDirectory || git.mkdirs()) { "Unable to prepare plugin marketplace origin" }
        File(git, "config").writeText(
            "[remote \"origin\"]\n\turl = https://github.com/${repository.owner}/${repository.repo}.git\n",
        )
    }

    private fun writeSource(directory: File, source: GitHubMarketplaceLocation) {
        File(directory, SOURCE_METADATA_FILE).writeText(
            JSONObject()
                .put("owner", source.repository.owner.lowercase())
                .put("repository", source.repository.repo.lowercase())
                .put("ref", source.ref ?: JSONObject.NULL)
                .put("path", source.path)
                .toString(),
        )
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Codex-Mobile")
        }

    private inline fun <T> HttpURLConnection.useResponse(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private companion object {
        const val NETWORK_TIMEOUT_MILLIS = 30_000
        const val MAX_METADATA_BYTES = 1L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
    }
}

private const val SOURCE_METADATA_FILE = ".codex-mobile-source.json"

internal fun findReusableMarketplaceSnapshot(root: File, source: GitHubMarketplaceLocation): File? =
    root.listFiles().orEmpty()
        .asSequence()
        .filter { it.isDirectory && !it.name.startsWith('.') }
        .filter(::isValidMarketplaceSnapshot)
        .filter { snapshotMatchesSource(it, source) }
        .maxByOrNull(File::lastModified)

private fun snapshotMatchesSource(directory: File, source: GitHubMarketplaceLocation): Boolean {
    val metadata = File(directory, SOURCE_METADATA_FILE)
    if (metadata.isFile) return runCatching {
        val value = JSONObject(metadata.readText())
        value.getString("owner").equals(source.repository.owner, true) &&
            value.getString("repository").equals(source.repository.repo, true) &&
            (if (value.isNull("ref")) null else value.getString("ref")) == source.ref &&
            value.optString("path") == source.path
    }.getOrDefault(false)
    if (source.ref != null || source.path.isNotEmpty()) return false
    return readSnapshotOrigin(directory) == source.repository.normalized()
}

private fun readSnapshotOrigin(directory: File): GitHubRepository? = runCatching {
    val url = File(directory, ".git/config").readLines()
        .first { it.trimStart().startsWith("url =") }
        .substringAfter('=')
        .trim()
    GitHubMarketplaceLocation.parse(url).repository.normalized()
}.getOrNull()

private fun GitHubRepository.normalized() = GitHubRepository(owner.lowercase(), repo.lowercase())

internal fun readMarketplaceName(directory: File): String {
    val manifest = File(directory, ".agents/plugins/marketplace.json")
    val name = Json.parseToJsonElement(manifest.readText()).jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    require(name.isNotBlank() && name.length <= 120) { "The plugin marketplace has no valid name" }
    return name
}

internal fun replaceMarketplaceSnapshot(staging: File, destination: File) {
    if (!destination.exists()) {
        check(staging.renameTo(destination)) { "Unable to activate plugin marketplace snapshot" }
        return
    }
    val previous = File(checkNotNull(destination.parentFile), ".previous-${UUID.randomUUID()}")
    check(destination.renameTo(previous)) { "Unable to replace plugin marketplace snapshot" }
    if (!staging.renameTo(destination)) {
        check(previous.renameTo(destination)) { "Unable to restore plugin marketplace snapshot" }
        error("Unable to activate plugin marketplace snapshot")
    }
    previous.deleteRecursively()
}

internal fun isValidMarketplaceSnapshot(directory: File): Boolean = runCatching {
    val root = directory.canonicalFile
    val manifest = File(root, ".agents/plugins/marketplace.json")
    if (!manifest.isFile || manifest.length() !in 1..(1L * 1024 * 1024) ||
        !File(root, ".git/config").isFile
    ) return false
    val plugins = Json.parseToJsonElement(manifest.readText()).jsonObject["plugins"]?.jsonArray ?: return false
    for (entry in plugins) {
        val source = entry.jsonObject["source"] ?: return false
        val localPath = when (source) {
            is JsonPrimitive -> source.contentOrNull
            is JsonObject -> if (source["source"]?.jsonPrimitive?.contentOrNull == "local") {
                source["path"]?.jsonPrimitive?.contentOrNull ?: return false
            } else null
            else -> null
        } ?: continue
        if (localPath.isBlank()) return false
        val plugin = File(root, localPath).canonicalFile
        if ((!plugin.toPath().startsWith(root.toPath()) && plugin != root) || !plugin.isDirectory) return false
    }
    true
}.getOrDefault(false)

internal data class GitHubMarketplaceLocation(
    val repository: GitHubRepository,
    val ref: String?,
    val path: String,
) {
    fun snapshotName(resolvedRef: String): String = MessageDigest.getInstance("SHA-256")
        .digest("${repository.owner.lowercase()}/${repository.repo.lowercase()}\u0000$resolvedRef\u0000$path".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        fun parse(value: String): GitHubMarketplaceLocation {
            val uri = URI(value.trim())
            require(
                uri.scheme == "https" && uri.host.equals("github.com", true) && uri.userInfo == null &&
                    uri.port == -1 && uri.query == null && uri.fragment == null,
            ) { "Use a public https://github.com repository or tree URL" }
            val parts = uri.path.split('/').filter(String::isNotEmpty)
            require(parts.size >= 2) { "Use a GitHub repository or tree URL" }
            val repository = GitHubRepository(parts[0], parts[1].removeSuffix(".git"))
            require(isGitHubSegment(repository.owner) && isGitHubSegment(repository.repo)) {
                "Invalid GitHub repository URL"
            }
            if (parts.size == 2) return GitHubMarketplaceLocation(repository, null, "")
            require(parts.size >= 4 && parts[2] == "tree") { "Use a GitHub repository or tree URL" }
            val ref = parts[3]
            val path = parts.drop(4)
            require(isGitHubSegment(ref) && path.all(::isGitHubSegment)) { "Invalid GitHub tree URL" }
            return GitHubMarketplaceLocation(repository, ref, path.joinToString("/"))
        }
    }
}

internal fun extractMarketplaceArchive(
    zip: ZipInputStream,
    requestedPath: String,
    destination: File,
    maximumBytes: Long = 100L * 1024 * 1024,
    maximumFiles: Int = 5_000,
) {
    val requested = requestedPath.trim('/').split('/').filter(String::isNotEmpty)
    val seen = mutableSetOf<String>()
    var files = 0
    var bytes = 0L
    while (true) {
        val entry = zip.nextEntry ?: break
        val archivePath = safeZipSegments(entry.name).drop(1)
        if (archivePath.size >= requested.size && archivePath.take(requested.size) == requested) {
            val relative = archivePath.drop(requested.size)
            if (relative.isNotEmpty()) {
                val relativePath = relative.joinToString("/")
                check(seen.add(relativePath)) { "The archive contains duplicate paths" }
                val output = relative.fold(destination) { parent, segment -> File(parent, segment) }
                check(output.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                    "Invalid archive path"
                }
                if (entry.isDirectory) {
                    check(output.isDirectory || output.mkdirs()) { "Unable to create marketplace directory" }
                } else {
                    files++
                    check(files <= maximumFiles) { "The marketplace contains too many files" }
                    output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                    output.outputStream().buffered().use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            check(bytes <= maximumBytes) { "The marketplace is too large" }
                            target.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        zip.closeEntry()
    }
}
