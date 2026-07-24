package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import io.github.ciurlaro.codexmobile.core.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.core.AgentSkill
import io.github.ciurlaro.codexmobile.core.AgentSkillChunk
import io.github.ciurlaro.codexmobile.core.AgentSkillPackage
import io.github.ciurlaro.codexmobile.core.AgentSkillPackageCatalog
import io.github.ciurlaro.codexmobile.core.AgentSkillPackageSource
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidSkillPackageManager internal constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val codexHome = File(appContext.noBackupFilesDir, "codex")
    private val skillsRoot = File(codexHome, "skills")
    private val cacheDirectory = File(appContext.cacheDir, "skill-catalog")
    private val catalogFile = File(cacheDirectory, "openai-curated.json")
    private val metadataFile = File(cacheDirectory, "openai-curated.properties")
    private val catalogMutex = Mutex()
    private val mutationMutex = Mutex()
    private val sourceCache = ConcurrentHashMap<String, ByteArray>()

    suspend fun listAvailable(
        installedNames: Set<String>,
        forceRefresh: Boolean = false,
    ): AgentSkillPackageCatalog = withContext(Dispatchers.IO) {
        catalogMutex.withLock {
            val cached = readCatalogCache()
            val remote = when {
                !forceRefresh && cached != null -> cached
                else -> runCatching { fetchCuratedCatalog(cached?.etag) }.getOrElse { error ->
                    if (cached == null) throw error
                    cached.copy(
                        freshness = AgentCatalogFreshness.STALE_CACHE,
                        errors = listOf(error.message ?: "OpenAI skills could not be refreshed"),
                    )
                }
            }
            AgentSkillPackageCatalog(
                skills = remote.skills
                    .filterNot { it.name in installedNames || File(skillsRoot, it.name).isDirectory }
                    .distinctBy(AgentSkillPackage::name),
                freshness = remote.freshness,
                errors = remote.errors,
            )
        }
    }

    suspend fun discoverGitHubSkills(url: String): List<AgentSkillPackage> = withContext(Dispatchers.IO) {
        val explicit = runCatching { GitHubSkillLocation.parse(url) }.getOrNull()
        val locations = explicit?.let(::listOf)
            ?: discoverRepositorySkills(GitHubRepository.parse(url))
        locations.map { packageFor(it, AgentSkillPackageSource.GITHUB) }
    }

    suspend fun readPackageSource(packageInfo: AgentSkillPackage, offset: Long = 0): AgentSkillChunk =
        withContext(Dispatchers.IO) {
            require(offset >= 0) { "Offset must not be negative" }
            val source = when (packageInfo.source) {
                AgentSkillPackageSource.CODEX_MOBILE ->
                    error("Built-in capabilities are managed as plugins")
                AgentSkillPackageSource.OPENAI, AgentSkillPackageSource.GITHUB -> readSource(packageInfo)
            }
            require(offset <= source.size) { "Offset exceeds skill source size" }
            val count = minOf(PREVIEW_CHUNK_BYTES, source.size - offset.toInt())
            val end = completeUtf8End(source, offset.toInt(), count)
            AgentSkillChunk(
                content = String(source, offset.toInt(), end - offset.toInt(), StandardCharsets.UTF_8),
                nextOffset = end.toLong().takeIf { it < source.size },
                totalBytes = source.size.toLong(),
            )
        }

    suspend fun install(packageInfo: AgentSkillPackage) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            requireSkillName(packageInfo.name)
            val destination = File(skillsRoot, packageInfo.name)
            check(!destination.exists()) { "A skill named ${packageInfo.name} is already installed" }
            when (packageInfo.source) {
                AgentSkillPackageSource.CODEX_MOBILE ->
                    error("Built-in capabilities are managed as plugins")
                AgentSkillPackageSource.OPENAI, AgentSkillPackageSource.GITHUB ->
                    installArchive(GitHubSkillLocation.parse(packageInfo.sourceUrl), destination)
            }
            sourceCache.remove(packageInfo.id)
        }
    }

    suspend fun uninstall(skill: AgentSkill) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val directory = uninstallableSkillDirectory(skill, skillsRoot)
            val removed = File(skillsRoot, ".removed-${directory.name}-${UUID.randomUUID()}")
            if (!directory.renameTo(removed)) {
                error("Unable to remove ${skill.displayName}")
            }
            check(removed.deleteRecursively()) { "The skill was detached but cleanup failed" }
        }
    }

    fun canUninstall(skill: AgentSkill): Boolean =
        runCatching { uninstallableSkillDirectory(skill, skillsRoot) }.isSuccess

    private fun readCatalogCache(): CachedCatalog? {
        if (!catalogFile.isFile || !metadataFile.isFile) return null
        return runCatching {
            val metadata = Properties().apply { metadataFile.inputStream().use(::load) }
            val fetchedAt = metadata.getProperty("fetchedAt")?.toLong() ?: return null
            val freshness = if (System.currentTimeMillis() - fetchedAt <= CACHE_TTL_MILLIS) {
                AgentCatalogFreshness.FRESH_CACHE
            } else {
                AgentCatalogFreshness.STALE_CACHE
            }
            CachedCatalog(
                skills = parseCuratedCatalog(catalogFile.readText()),
                freshness = freshness,
                etag = metadata.getProperty("etag"),
            )
        }.getOrNull()
    }

    private fun fetchCuratedCatalog(etag: String?): CachedCatalog {
        val connection = openConnection(CURATED_API_URL).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            etag?.let { setRequestProperty("If-None-Match", it) }
        }
        return connection.useResponse { response ->
            if (response.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                val cached = readCatalogCache() ?: error("Skill catalog cache disappeared")
                writeCatalogCache(catalogFile.readText(), etag)
                cached.copy(freshness = AgentCatalogFreshness.LIVE)
            } else {
                check(response.responseCode in 200..299) { "GitHub returned HTTP ${response.responseCode}" }
                val body = LimitedInputStream(response.inputStream, MAX_CATALOG_BYTES).bufferedReader().use { it.readText() }
                val skills = parseCuratedCatalog(body)
                writeCatalogCache(body, response.getHeaderField("ETag"))
                CachedCatalog(skills, AgentCatalogFreshness.LIVE, response.getHeaderField("ETag"))
            }
        }
    }

    private fun parseCuratedCatalog(json: String): List<AgentSkillPackage> {
        val values = JSONArray(json)
        return buildList {
            repeat(values.length()) { index ->
                val item = values.getJSONObject(index)
                val name = item.optString("name")
                if (item.optString("type") == "dir" && isSkillName(name)) {
                    add(packageFor(GitHubSkillLocation("openai", "skills", "main", "skills/.curated/$name"), AgentSkillPackageSource.OPENAI))
                }
            }
        }.sortedBy(AgentSkillPackage::displayName)
    }

    private fun discoverRepositorySkills(repository: GitHubRepository): List<GitHubSkillLocation> {
        val metadata = readGitHubJson("https://api.github.com/repos/${repository.owner}/${repository.repo}")
        val ref = metadata.getString("default_branch")
        require(isGitHubSegment(ref)) { "The repository default branch is not supported" }
        val encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val tree = readGitHubJson(
            "https://api.github.com/repos/${repository.owner}/${repository.repo}/git/trees/$encodedRef?recursive=1",
        )
        check(!tree.optBoolean("truncated")) { "The GitHub repository is too large to inspect" }
        val rawEntries = tree.getJSONArray("tree").let { entries ->
            List(entries.length()) { index ->
                entries.getJSONObject(index).let { item ->
                    GitHubTreeEntry(item.optString("type"), item.optString("path"))
                }
            }
        }
        return parseGitHubSkillTree(rawEntries, repository, ref).also {
            check(it.isNotEmpty()) { "No skill folders with a SKILL.md file were found" }
        }
    }

    private fun readGitHubJson(url: String): JSONObject {
        val connection = openConnection(url).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return connection.useResponse { response ->
            check(response.responseCode in 200..299) { "GitHub returned HTTP ${response.responseCode}" }
            val body = LimitedInputStream(response.inputStream, MAX_CATALOG_BYTES)
                .bufferedReader().use { it.readText() }
            JSONObject(body)
        }
    }

    private fun writeCatalogCache(body: String, etag: String?) {
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Unable to prepare skill catalog cache" }
        writeAtomically(catalogFile, body.toByteArray(StandardCharsets.UTF_8))
        val metadata = Properties().apply {
            setProperty("fetchedAt", System.currentTimeMillis().toString())
            etag?.let { setProperty("etag", it) }
        }
        val next = File(cacheDirectory, ".${metadataFile.name}.next")
        next.outputStream().use { metadata.store(it, null) }
        check(next.renameReplacing(metadataFile)) { "Unable to update skill catalog metadata" }
    }

    private fun readSource(packageInfo: AgentSkillPackage): ByteArray = sourceCache[packageInfo.id] ?: run {
        val location = GitHubSkillLocation.parse(packageInfo.sourceUrl)
        val path = location.path.takeIf(String::isNotEmpty)?.let { "$it/" }.orEmpty()
        val url = "https://raw.githubusercontent.com/${location.owner}/${location.repo}/${location.ref}/${path}SKILL.md"
        val bytes = openConnection(url).useResponse { response ->
            check(response.responseCode in 200..299) { "GitHub returned HTTP ${response.responseCode}" }
            LimitedInputStream(response.inputStream, MAX_SKILL_MD_BYTES).use { it.readBytes() }
        }
        validateUtf8(bytes)
        sourceCache.putIfAbsent(packageInfo.id, bytes) ?: bytes
    }

    private fun packageFor(location: GitHubSkillLocation, source: AgentSkillPackageSource): AgentSkillPackage {
        val name = location.path.substringAfterLast('/').ifEmpty { location.repo }
        requireSkillName(name)
        return AgentSkillPackage(
            id = "${source.name.lowercase()}:${location.owner}/${location.repo}/${location.ref}/${location.path}",
            name = name,
            displayName = name.humanName(),
            description = if (source == AgentSkillPackageSource.OPENAI) "OpenAI curated skill" else "Skill from GitHub",
            source = source,
            sourceUrl = location.url,
        )
    }

    private fun installArchive(location: GitHubSkillLocation, destination: File) {
        check(skillsRoot.isDirectory || skillsRoot.mkdirs()) { "Unable to prepare user skills directory" }
        val staging = File(skillsRoot, ".install-${destination.name}-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Unable to prepare skill installation" }
        try {
            val connection = openConnection(
                "https://codeload.github.com/${location.owner}/${location.repo}/zip/${location.ref}",
            )
            connection.useResponse { response ->
                check(response.responseCode in 200..299) { "GitHub returned HTTP ${response.responseCode}" }
                val counted = LimitedInputStream(response.inputStream, MAX_DOWNLOAD_BYTES)
                ZipInputStream(counted.buffered()).use { zip -> extractSelectedSkill(zip, location.path, staging) }
            }
            check(staging.renameTo(destination)) { "Unable to activate installed skill" }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
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

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val next = File(file.parentFile, ".${file.name}.next")
        next.writeBytes(bytes)
        check(next.renameReplacing(file)) { "Unable to update cache" }
    }

    private fun File.renameReplacing(destination: File): Boolean {
        if (destination.exists() && !destination.delete()) return false
        return renameTo(destination)
    }

    private data class CachedCatalog(
        val skills: List<AgentSkillPackage>,
        val freshness: AgentCatalogFreshness,
        val etag: String?,
        val errors: List<String> = emptyList(),
    )

    private companion object {
        const val CURATED_API_URL = "https://api.github.com/repos/openai/skills/contents/skills/.curated?ref=main"
        const val CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L
        const val NETWORK_TIMEOUT_MILLIS = 30_000
        const val PREVIEW_CHUNK_BYTES = 32 * 1024
        const val MAX_CATALOG_BYTES = 5L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024
        const val MAX_SKILL_MD_BYTES = 1L * 1024 * 1024
    }
}

internal fun extractSelectedSkill(
    zip: ZipInputStream,
    requestedPath: String,
    staging: File,
    maximumBytes: Long = 50L * 1024 * 1024,
    maximumFiles: Int = 1_000,
    maximumSkillBytes: Long = 1L * 1024 * 1024,
) {
    val requested = requestedPath.trim('/').split('/').filter(String::isNotEmpty)
    val seen = mutableSetOf<String>()
    var files = 0
    var bytes = 0L
    while (true) {
        val entry = zip.nextEntry ?: break
        val segments = safeZipSegments(entry.name)
        val archivePath = segments.drop(1)
        if (archivePath.size >= requested.size && archivePath.take(requested.size) == requested) {
            val relative = archivePath.drop(requested.size)
            if (relative.isNotEmpty()) {
                val relativePath = relative.joinToString("/")
                check(seen.add(relativePath)) { "The archive contains duplicate paths" }
                val output = relative.fold(staging) { parent, segment -> File(parent, segment) }
                check(output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Invalid archive path" }
                if (entry.isDirectory) {
                    check(output.isDirectory || output.mkdirs()) { "Unable to create skill directory" }
                } else {
                    files++
                    check(files <= maximumFiles) { "The selected skill contains too many files" }
                    output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                    output.outputStream().buffered().use { target ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            check(bytes <= maximumBytes) { "The selected skill is too large" }
                            target.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        zip.closeEntry()
    }
    val source = File(staging, "SKILL.md")
    check(source.isFile && source.length() <= maximumSkillBytes) { "The selected folder has no valid SKILL.md" }
    validateUtf8(source.readBytes())
}

internal fun uninstallableSkillDirectory(skill: AgentSkill, skillsRoot: File): File {
    val source = File(skill.path).canonicalFile
    require(source.name == "SKILL.md" && source.isFile) { "Skill source is not removable" }
    val directory = source.parentFile ?: error("Skill directory is missing")
    requireSkillName(directory.name)
    require(directory.parentFile?.canonicalFile == skillsRoot.canonicalFile) {
        "Only independently installed user skills can be removed"
    }
    return directory
}

internal data class GitHubSkillLocation(
    val owner: String,
    val repo: String,
    val ref: String,
    val path: String,
) {
    val url: String
        get() = "https://github.com/$owner/$repo/tree/$ref" +
            path.takeIf(String::isNotEmpty)?.let { "/$it" }.orEmpty()

    companion object {
        fun parse(value: String): GitHubSkillLocation {
            val uri = URI(value.trim())
            require(
                uri.scheme == "https" && uri.host.equals("github.com", true) && uri.userInfo == null &&
                    uri.port == -1 && uri.query == null && uri.fragment == null,
            ) { "Use a public https://github.com/<owner>/<repo>/tree/<ref>/<path> URL" }
            val parts = uri.path.split('/').filter(String::isNotEmpty)
            require(parts.size >= 4 && parts[2] == "tree") { "GitHub URL must point to a skill folder" }
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            val ref = parts[3]
            val path = parts.drop(4).joinToString("/")
            require(listOf(owner, repo, ref).all(::isGitHubSegment)) { "Invalid GitHub skill URL" }
            path.split('/').filter(String::isNotEmpty).forEach {
                require(isGitHubSegment(it)) { "Invalid GitHub skill path" }
            }
            requireSkillName(path.substringAfterLast('/').ifEmpty { repo })
            return GitHubSkillLocation(owner, repo, ref, path)
        }
    }
}

internal data class GitHubRepository(val owner: String, val repo: String) {
    companion object {
        fun parse(value: String): GitHubRepository {
            val uri = URI(value.trim())
            require(
                uri.scheme == "https" && uri.host.equals("github.com", true) && uri.userInfo == null &&
                    uri.port == -1 && uri.query == null && uri.fragment == null,
            ) { "Use a public GitHub repository or skill folder URL" }
            val parts = uri.path.split('/').filter(String::isNotEmpty)
            require(parts.size == 2) { "GitHub URL must point to a repository or skill folder" }
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            require(isGitHubSegment(owner) && isGitHubSegment(repo)) { "Invalid GitHub repository URL" }
            return GitHubRepository(owner, repo)
        }
    }
}

internal data class GitHubTreeEntry(val type: String, val path: String)

internal fun parseGitHubSkillTree(
    entries: List<GitHubTreeEntry>,
    repository: GitHubRepository,
    ref: String,
): List<GitHubSkillLocation> = buildList {
    entries.forEach { item ->
        val source = item.path
        if (item.type != "blob" || source != "SKILL.md" && !source.endsWith("/SKILL.md")) return@forEach
        val path = source.removeSuffix("SKILL.md").removeSuffix("/")
        val segments = path.split('/').filter(String::isNotEmpty)
        if (segments.any { it.startsWith('.') || !isGitHubSegment(it) }) return@forEach
        add(GitHubSkillLocation(repository.owner, repository.repo, ref, path))
    }
}.distinctBy(GitHubSkillLocation::path).sortedBy { it.path }

private class LimitedInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) add(it.toLong()) }

    private fun add(value: Long) {
        count += value
        check(count <= maximum) { "Download exceeds the allowed size" }
    }
}

private fun safeZipSegments(name: String): List<String> {
    require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && '\u0000' !in name) {
        "Invalid archive path"
    }
    return name.split('/').filter(String::isNotEmpty).also { parts ->
        require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) { "Invalid archive path" }
    }
}

private fun requireSkillName(value: String) {
    require(isSkillName(value)) { "Invalid skill name" }
}

private fun isSkillName(value: String): Boolean =
    value.length in 1..64 && value.first().isLetterOrDigit() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

private fun isGitHubSegment(value: String): Boolean =
    value.isNotEmpty() && value.length <= 128 && value != "." && value != ".." &&
        value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

private fun String.humanName(): String =
    split('-', '_').filter(String::isNotEmpty).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

private fun validateUtf8(bytes: ByteArray) {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
}

private fun completeUtf8End(bytes: ByteArray, offset: Int, count: Int): Int {
    var end = offset + count
    if (end == bytes.size || count == 0) return end
    var lead = end - 1
    while (lead >= offset && bytes[lead].toInt() and 0xC0 == 0x80) lead--
    if (lead < offset) return offset
    val expected = when (bytes[lead].toInt() and 0xFF) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 1
    }
    if (end - lead < expected) end = lead
    return end
}
