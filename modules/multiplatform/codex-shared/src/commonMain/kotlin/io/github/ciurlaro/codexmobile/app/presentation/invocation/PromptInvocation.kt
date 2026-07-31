package io.github.ciurlaro.codexmobile.app.presentation.invocation

import io.github.ciurlaro.codexmobile.agent.AgentInvocation

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
