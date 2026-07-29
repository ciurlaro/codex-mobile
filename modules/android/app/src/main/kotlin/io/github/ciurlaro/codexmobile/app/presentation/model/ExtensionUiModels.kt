package io.github.ciurlaro.codexmobile.app.presentation.model

import io.github.ciurlaro.codexmobile.app.presentation.invocation.readableTitle
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginSummary
import io.github.ciurlaro.codexmobile.agent.AgentSkill

enum class ExtensionType(val label: String) {
    SKILLS("Skills"), PLUGINS("Plugins"),
}

enum class ExtensionStatus(val label: String) {
    INSTALLED("Installed"), SETUP_PENDING("Setup pending"), UNINSTALLED("Market"), UNAVAILABLE("Unavailable"),
}

enum class PluginCatalogStatus { NOT_LOADED, CONNECTING, LOADING, LIVE, STALE, ERROR }

sealed interface ExtensionRemoval {
    val displayName: String

    data class Skill(val skill: AgentSkill) : ExtensionRemoval {
        override val displayName: String get() = skill.readableTitle()
    }

    data class Plugin(
        val plugin: AgentPluginReference,
        override val displayName: String,
    ) : ExtensionRemoval
}

data class ExtensionActionError(val operationId: String, val message: String)

data class ExtensionNotice(val message: String, val isError: Boolean = false)

internal fun ExtensionNotice?.afterExpiry(expiring: ExtensionNotice): ExtensionNotice? =
    takeUnless { it == expiring }

data class CustomExtensionSource(
    val id: String,
    val url: String,
    val marketplaceName: String?,
    val supportsSkills: Boolean,
    val supportsPlugins: Boolean,
)

data class ExtensionSourceSelection(
    val knownIds: Set<String>,
    val enabledIds: Set<String>,
    val customSources: List<CustomExtensionSource> = emptyList(),
)

data class ExtensionSourceUi(
    val id: String,
    val displayName: String,
    val description: String,
    val capabilityLabel: String,
    val enabled: Boolean,
    val isDefault: Boolean = false,
    val isCustom: Boolean = false,
)

internal const val CODEX_MOBILE_PLUGIN_SOURCE_ID = "codex-mobile"
internal const val OPENAI_PLUGIN_SOURCE_ID = "openai-curated-remote"
internal const val CODEX_MOBILE_PLUGIN_SOURCE_URL = "https://github.com/ciurlaro/codex-mobile-plugins"

private val BUILT_IN_PLUGIN_SOURCE_IDS = setOf(CODEX_MOBILE_PLUGIN_SOURCE_ID, OPENAI_PLUGIN_SOURCE_ID)

internal fun initialExtensionSourceSelection(
    savedKnownIds: Set<String>?,
    savedEnabledIds: Set<String>?,
    savedCustomSources: List<CustomExtensionSource> = emptyList(),
    appWasUpgraded: Boolean,
): ExtensionSourceSelection {
    if (savedKnownIds != null && savedEnabledIds != null) {
        val known = savedKnownIds.map(::canonicalPluginSourceId).toSet() +
            savedCustomSources.map(CustomExtensionSource::id) + BUILT_IN_PLUGIN_SOURCE_IDS
        return ExtensionSourceSelection(
            known,
            savedEnabledIds.map(::canonicalPluginSourceId).toSet() intersect known,
            savedCustomSources.distinctBy(CustomExtensionSource::id),
        )
    }
    val customIds = savedCustomSources.map(CustomExtensionSource::id).toSet()
    return ExtensionSourceSelection(
        knownIds = BUILT_IN_PLUGIN_SOURCE_IDS + customIds,
        enabledIds = (if (appWasUpgraded) BUILT_IN_PLUGIN_SOURCE_IDS else setOf(CODEX_MOBILE_PLUGIN_SOURCE_ID)) +
            customIds,
        customSources = savedCustomSources.distinctBy(CustomExtensionSource::id),
    )
}

internal fun canonicalPluginSourceId(marketplaceName: String): String = when {
    marketplaceName == CODEX_MOBILE_PLUGIN_SOURCE_ID -> CODEX_MOBILE_PLUGIN_SOURCE_ID
    marketplaceName == "openai" || marketplaceName.startsWith("openai-curated") -> OPENAI_PLUGIN_SOURCE_ID
    else -> marketplaceName
}

internal fun extensionSourceItems(selection: ExtensionSourceSelection): List<ExtensionSourceUi> =
    selection.knownIds.mapNotNull { id ->
        when (id) {
            CODEX_MOBILE_PLUGIN_SOURCE_ID -> ExtensionSourceUi(
                id = id,
                displayName = "Codex Mobile",
                description = "Official extensions curated for Codex Mobile.",
                capabilityLabel = "Plugins",
                enabled = id in selection.enabledIds,
                isDefault = true,
            )
            OPENAI_PLUGIN_SOURCE_ID -> ExtensionSourceUi(
                id = id,
                displayName = "OpenAI curated",
                description = "High-quality extensions and skills curated by OpenAI.",
                capabilityLabel = "Skills + Plugins",
                enabled = id in selection.enabledIds,
            )
            else -> id.takeIf(String::isNotBlank)?.let { sourceId ->
                val custom = selection.customSources.firstOrNull { it.id == sourceId }
                ExtensionSourceUi(
                    id = sourceId,
                    displayName = custom?.url?.githubRepositoryName()
                        ?: sourceId.replace('-', ' ').replace('_', ' ').replaceFirstChar(Char::uppercase),
                    description = custom?.url ?: "Custom GitHub plugin marketplace.",
                    capabilityLabel = when {
                        custom == null -> "Plugins"
                        custom.supportsSkills && custom.supportsPlugins -> "Skills + Plugins"
                        custom.supportsSkills -> "Skills"
                        else -> "Plugins"
                    },
                    enabled = sourceId in selection.enabledIds,
                    isCustom = true,
                )
            }
        }
    }.sortedWith(compareBy<ExtensionSourceUi> {
        when (it.id) {
            CODEX_MOBILE_PLUGIN_SOURCE_ID -> 0
            OPENAI_PLUGIN_SOURCE_ID -> 1
            else -> 2
        }
    }.thenBy(ExtensionSourceUi::displayName))

internal fun ExtensionSourceSelection.enabledMarketplaceNames(): Set<String> = buildSet {
    enabledIds.forEach { id ->
        when (id) {
            CODEX_MOBILE_PLUGIN_SOURCE_ID, OPENAI_PLUGIN_SOURCE_ID -> add(id)
            else -> customSources.firstOrNull { it.id == id }?.marketplaceName?.let(::add) ?: add(id)
        }
    }
}

internal fun AgentPluginSummary.uninstalledStatus(
    installedIds: Set<String>,
    unavailableIds: Set<String>,
): ExtensionStatus? = when {
    reference.id in installedIds -> null
    !available || reference.id in unavailableIds -> ExtensionStatus.UNAVAILABLE
    else -> ExtensionStatus.UNINSTALLED
}

internal fun reconcilePendingPluginSetups(
    pending: Map<String, Set<String>>,
    connectors: List<AgentConnector>,
    installedPluginIds: Set<String>? = null,
): Map<String, Set<String>> {
    val accessibleConnectorIds = connectors.filter { it.isAccessible }.mapTo(mutableSetOf()) { it.id }
    return pending.mapNotNull { (pluginId, connectorIds) ->
        val remaining = connectorIds - accessibleConnectorIds
        (pluginId to remaining).takeIf {
            remaining.isNotEmpty() && (installedPluginIds == null || pluginId in installedPluginIds)
        }
    }.toMap()
}

private fun String.githubRepositoryName(): String = trimEnd('/').substringAfterLast('/').removeSuffix(".git")
    .replace('-', ' ').replace('_', ' ').replaceFirstChar(Char::uppercase)
