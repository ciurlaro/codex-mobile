package io.github.ciurlaro.codexmobile.app.presentation.model

import io.github.ciurlaro.codexmobile.app.presentation.invocation.readableTitle
import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import io.github.ciurlaro.codexmobile.core.AgentSkill

enum class ExtensionFilter(val label: String) {
    ALL("All"), SKILLS("Skills"), PLUGINS("Plugins"),
}

enum class ExtensionSection(val label: String) {
    INSTALLED("Installed"), DISCOVER("Discover"),
}

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

data class PluginSourceSelection(
    val knownIds: Set<String>,
    val enabledIds: Set<String>,
)

data class PluginSourceUi(
    val id: String,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
    val isDefault: Boolean = false,
    val isCustom: Boolean = false,
)

internal const val CODEX_MOBILE_PLUGIN_SOURCE_ID = "codex-mobile"
internal const val OPENAI_PLUGIN_SOURCE_ID = "openai-curated-remote"
internal const val CODEX_MOBILE_PLUGIN_SOURCE_URL = "https://github.com/ciurlaro/codex-mobile-plugins"

private val BUILT_IN_PLUGIN_SOURCE_IDS = setOf(CODEX_MOBILE_PLUGIN_SOURCE_ID, OPENAI_PLUGIN_SOURCE_ID)

internal fun initialPluginSourceSelection(
    savedKnownIds: Set<String>?,
    savedEnabledIds: Set<String>?,
    appWasUpgraded: Boolean,
): PluginSourceSelection {
    if (savedKnownIds != null && savedEnabledIds != null) {
        val known = savedKnownIds.map(::canonicalPluginSourceId).toSet() + BUILT_IN_PLUGIN_SOURCE_IDS
        return PluginSourceSelection(known, savedEnabledIds.map(::canonicalPluginSourceId).toSet() intersect known)
    }
    return PluginSourceSelection(
        knownIds = BUILT_IN_PLUGIN_SOURCE_IDS,
        enabledIds = if (appWasUpgraded) BUILT_IN_PLUGIN_SOURCE_IDS else setOf(CODEX_MOBILE_PLUGIN_SOURCE_ID),
    )
}

internal fun canonicalPluginSourceId(marketplaceName: String): String = when {
    marketplaceName == CODEX_MOBILE_PLUGIN_SOURCE_ID -> CODEX_MOBILE_PLUGIN_SOURCE_ID
    marketplaceName == "openai" || marketplaceName.startsWith("openai-curated") -> OPENAI_PLUGIN_SOURCE_ID
    else -> marketplaceName
}

internal fun pluginSourceItems(selection: PluginSourceSelection): List<PluginSourceUi> =
    selection.knownIds.mapNotNull { id ->
        when (id) {
            CODEX_MOBILE_PLUGIN_SOURCE_ID -> PluginSourceUi(
                id = id,
                displayName = "Codex Mobile",
                description = "Official extensions curated for Codex Mobile.",
                enabled = id in selection.enabledIds,
                isDefault = true,
            )
            OPENAI_PLUGIN_SOURCE_ID -> PluginSourceUi(
                id = id,
                displayName = "OpenAI curated",
                description = "High-quality extensions and skills curated by OpenAI.",
                enabled = id in selection.enabledIds,
            )
            else -> id.takeIf(String::isNotBlank)?.let {
                PluginSourceUi(
                    id = it,
                    displayName = it.replace('-', ' ').replace('_', ' ').replaceFirstChar(Char::uppercase),
                    description = "Custom GitHub plugin marketplace.",
                    enabled = it in selection.enabledIds,
                    isCustom = true,
                )
            }
        }
    }.sortedWith(compareBy<PluginSourceUi> {
        when (it.id) {
            CODEX_MOBILE_PLUGIN_SOURCE_ID -> 0
            OPENAI_PLUGIN_SOURCE_ID -> 1
            else -> 2
        }
    }.thenBy(PluginSourceUi::displayName))
