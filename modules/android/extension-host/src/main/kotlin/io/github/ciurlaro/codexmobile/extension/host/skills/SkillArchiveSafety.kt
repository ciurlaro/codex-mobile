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
