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
