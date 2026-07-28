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
}
