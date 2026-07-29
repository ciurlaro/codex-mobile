package io.github.ciurlaro.codexmobile.app.presentation.mapper

import io.github.ciurlaro.codexmobile.app.presentation.model.ChatMessage
import io.github.ciurlaro.codexmobile.agent.AgentMessage

internal fun AgentMessage.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    role = role,
    text = text,
    collaborationMode = collaborationMode,
    reasoning = reasoning,
    plan = plan,
    shellCommand = shellCommand,
    exitCode = exitCode,
    capabilities = capabilities,
    invocations = invocations,
)
