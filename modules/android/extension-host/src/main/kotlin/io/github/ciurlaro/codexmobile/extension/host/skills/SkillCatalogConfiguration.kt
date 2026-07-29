package io.github.ciurlaro.codexmobile.extension.host

import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentSkillPackage

internal data class CachedCatalog(
    val skills: List<AgentSkillPackage>,
    val freshness: AgentCatalogFreshness,
    val etag: String?,
    val errors: List<String> = emptyList(),
)

internal const val CURATED_API_URL =
    "https://api.github.com/repos/openai/skills/contents/skills/.curated?ref=main"
internal const val CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L
internal const val NETWORK_TIMEOUT_MILLIS = 30_000
internal const val PREVIEW_CHUNK_BYTES = 32 * 1024
internal const val MAX_CATALOG_BYTES = 5L * 1024 * 1024
internal const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024
internal const val MAX_SKILL_MD_BYTES = 1L * 1024 * 1024
