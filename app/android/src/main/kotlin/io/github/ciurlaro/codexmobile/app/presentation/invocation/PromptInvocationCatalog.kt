package io.github.ciurlaro.codexmobile.app.presentation.invocation

import io.github.ciurlaro.codexmobile.app.presentation.input.invocationToken
import io.github.ciurlaro.codexmobile.app.presentation.state.AppUiState
import io.github.ciurlaro.codexmobile.core.AgentInvocation
import io.github.ciurlaro.codexmobile.core.AgentSkill

internal fun AppUiState.promptInvocations(kind: PromptInvocationKind? = null): List<PromptInvocation> = buildList {
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

internal fun AppUiState.promptInvocation(invocation: AgentInvocation): PromptInvocation =
    promptInvocations().firstOrNull { it.invocation.key == invocation.key } ?: PromptInvocation(
        invocation = invocation,
        kind = if (invocation is AgentInvocation.Skill) PromptInvocationKind.SKILL else PromptInvocationKind.PLUGIN,
        title = invocation.name.readableInvocationName(),
        provider = invocation.name.providerLabel(),
    )

internal fun AppUiState.availablePromptInvocations(kind: PromptInvocationKind? = null): List<PromptInvocation> =
    promptInvocations(kind).filter { candidate ->
        selectedInvocations.none { it.key == candidate.invocation.key }
    }

internal fun AppUiState.recentPromptInvocations(kind: PromptInvocationKind? = null): List<PromptInvocation> {
    val available = availablePromptInvocations(kind).associateBy { it.invocation.key }
    return recentInvocationKeys.mapNotNull(available::get)
}

internal fun List<String>.withRecentInvocation(key: String, limit: Int = 4): List<String> =
    (listOf(key) + this).distinct().take(limit)

internal fun AppUiState.suggestedInvocationItems(): List<PromptInvocation> {
    if (draft.startsWith('!')) return emptyList()
    val match = invocationToken.find(draft) ?: return emptyList()
    val query = match.groupValues[2]
    val kind = when (match.groupValues[1].single()) {
        '$' -> PromptInvocationKind.SKILL
        '@' -> PromptInvocationKind.PLUGIN
        else -> return emptyList()
    }
    val recentOrder = recentInvocationKeys.withIndex().associate { it.value to it.index }
    return availablePromptInvocations(kind)
        .filter { query.isEmpty() || it.searchableText.contains(query, ignoreCase = true) }
        .sortedWith(compareBy({ recentOrder[it.invocation.key] ?: Int.MAX_VALUE }, { it.title.lowercase() }))
        .take(5)
}

internal fun AppUiState.suggestedInvocations(): List<AgentInvocation> =
    suggestedInvocationItems().map(PromptInvocation::invocation)

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
