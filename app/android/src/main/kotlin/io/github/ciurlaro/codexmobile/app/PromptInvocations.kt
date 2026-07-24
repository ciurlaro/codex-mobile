package io.github.ciurlaro.codexmobile.app

import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentSkill

internal enum class PromptInvocationKind { SKILL, PLUGIN }

internal data class PromptInvocation(
    val invocation: AgentInvocation,
    val kind: PromptInvocationKind,
    val title: String,
    val provider: String? = null,
    val description: String = "",
) {
    val subtitle: String?
        get() = listOfNotNull(provider, description.takeIf(String::isNotBlank))
            .joinToString(" · ")
            .takeIf(String::isNotBlank)

    val searchableText: String
        get() = listOf(title, provider, description, invocation.name).joinToString(" ")
}

internal fun MainUiState.promptInvocations(kind: PromptInvocationKind? = null): List<PromptInvocation> = buildList {
    if (kind != PromptInvocationKind.PLUGIN) {
        skills.filter(AgentSkill::enabled).forEach { skill ->
            add(
                PromptInvocation(
                    invocation = AgentInvocation.Skill(skill.name, skill.path),
                    kind = PromptInvocationKind.SKILL,
                    title = skill.readableTitle(),
                    provider = skill.providerLabel(),
                    description = skill.description,
                ),
            )
        }
    }
    if (kind != PromptInvocationKind.SKILL) {
        plugins.filter { it.installed && it.enabled }.forEach { plugin ->
            add(
                PromptInvocation(
                    invocation = AgentInvocation.Plugin(plugin.reference.name, plugin.reference.uri),
                    kind = PromptInvocationKind.PLUGIN,
                    title = plugin.displayName.ifBlank { plugin.reference.name.humanizeIdentifier() },
                    description = plugin.description,
                ),
            )
        }
    }
}

internal fun MainUiState.promptInvocation(invocation: AgentInvocation): PromptInvocation =
    promptInvocations().firstOrNull { it.invocation.key == invocation.key } ?: PromptInvocation(
        invocation = invocation,
        kind = if (invocation is AgentInvocation.Skill) PromptInvocationKind.SKILL else PromptInvocationKind.PLUGIN,
        title = invocation.name.readableInvocationName(),
        provider = invocation.name.providerLabel(),
    )

internal fun MainUiState.availablePromptInvocations(kind: PromptInvocationKind? = null): List<PromptInvocation> =
    promptInvocations(kind).filter { candidate ->
        selectedInvocations.none { it.key == candidate.invocation.key }
    }

internal fun MainUiState.recentPromptInvocations(kind: PromptInvocationKind? = null): List<PromptInvocation> {
    val available = availablePromptInvocations(kind).associateBy { it.invocation.key }
    return recentInvocationKeys.mapNotNull(available::get)
}

internal fun List<String>.withRecentInvocation(key: String, limit: Int = 4): List<String> =
    (listOf(key) + this).distinct().take(limit)

internal fun PromptInvocation.glyph(): IconGlyph =
    if (kind == PromptInvocationKind.SKILL) IconGlyph.SPARKLES else IconGlyph.PUZZLE

internal fun PromptInvocation.accent() =
    if (kind == PromptInvocationKind.SKILL) ChatColors.SkillAccent else ChatColors.PluginAccent

internal fun AgentSkill.readableTitle(): String {
    val fallback = name.readableInvocationName()
    return displayName.trim().takeIf { it.isNotEmpty() && ':' !in it } ?: fallback
}

internal fun AgentSkill.providerLabel(): String? = name.providerLabel()
    ?.takeUnless { readableTitle().startsWith(it, ignoreCase = true) }

private fun String.providerLabel(): String? = substringBefore(':')
    .takeIf { ':' in this }
    ?.humanizeIdentifier()

private fun String.readableInvocationName(): String {
    val provider = substringBefore(':').takeIf { ':' in this }
    val localName = substringAfter(':', this).removePrefix(provider?.let { "$it-" }.orEmpty())
    return localName.humanizeIdentifier()
}

private fun String.humanizeIdentifier(): String = replace('-', ' ')
    .replace('_', ' ')
    .trim()
    .replaceFirstChar(Char::uppercase)
