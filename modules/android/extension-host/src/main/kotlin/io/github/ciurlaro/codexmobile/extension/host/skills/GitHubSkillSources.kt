package io.github.ciurlaro.codexmobile.extension.host

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillChunk
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackageCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackageSource
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

internal class LimitedInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) add(it.toLong()) }

    private fun add(value: Long) {
        count += value
        check(count <= maximum) { "Download exceeds the allowed size" }
    }
}

internal fun safeZipSegments(name: String): List<String> {
    require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && '\u0000' !in name) {
        "Invalid archive path"
    }
    return name.split('/').filter(String::isNotEmpty).also { parts ->
        require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) { "Invalid archive path" }
    }
}

internal fun requireSkillName(value: String) {
    require(isSkillName(value)) { "Invalid skill name" }
}

internal fun isSkillName(value: String): Boolean =
    value.length in 1..64 && value.first().isLetterOrDigit() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

internal fun isGitHubSegment(value: String): Boolean =
    value.isNotEmpty() && value.length <= 128 && value != "." && value != ".." &&
        value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

internal fun String.humanName(): String =
    split('-', '_').filter(String::isNotEmpty).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

internal fun validateUtf8(bytes: ByteArray) {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
}

internal fun completeUtf8End(bytes: ByteArray, offset: Int, count: Int): Int {
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
