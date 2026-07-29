package io.github.ciurlaro.codexmobile.app.presentation.input

import io.github.ciurlaro.codexmobile.agent.AgentInvocation

internal fun String.shellCommandOrNull(): String? =
    takeIf { it.startsWith('!') }?.drop(1)?.trim()

internal data class PlanCommand(val prompt: String)

internal fun String.planCommandOrNull(): PlanCommand? {
    val match = PLAN_PREFIX.find(this) ?: return null
    return PlanCommand(drop(match.range.last + 1).trimStart())
}

internal fun String.withoutActiveInvocationToken(invocation: AgentInvocation): String {
    val marker = if (invocation is AgentInvocation.Skill) '$' else '@'
    val match = invocationToken.find(this) ?: return this
    if (match.groupValues[1].singleOrNull() != marker) return this
    val markerIndex = match.range.first + match.value.indexOf(marker)
    return removeRange(markerIndex, length).trimEnd()
}

internal val invocationToken = Regex("(?:^|\\s)([@${'$'}])([A-Za-z0-9_:-]*)${'$'}")

private val PLAN_PREFIX = Regex("^/plan(?:\\s+|${'$'})", RegexOption.IGNORE_CASE)
