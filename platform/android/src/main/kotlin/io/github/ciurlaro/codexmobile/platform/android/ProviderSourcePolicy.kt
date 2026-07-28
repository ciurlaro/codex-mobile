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

internal fun kotlinx.serialization.json.JsonObject.requireOnly(vararg names: String) {
    require(keys.all { it in names }) { "Provider manifest contains an unsupported field" }
}

@Suppress("DEPRECATION")
internal fun android.content.pm.PackageInfo.compatVersionCode(): Int =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode.toInt() else versionCode

internal val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
internal const val CANONICAL_PROVIDER_REPOSITORY = "ciurlaro/codex-mobile-plugins"
