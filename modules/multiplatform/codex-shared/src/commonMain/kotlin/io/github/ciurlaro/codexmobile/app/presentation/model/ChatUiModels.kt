package io.github.ciurlaro.codexmobile.app.presentation.model

import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentHookActivity
import io.github.ciurlaro.codexmobile.agent.AgentPlanProgress

enum class ChatSelector { TAGS, SKILLS, PLUGINS, EFFORT, MODEL, SPEED, APPROVAL }

data class ChatMessage(
    val id: String,
    val role: AgentMessageRole,
    val text: String,
    val collaborationMode: AgentCollaborationMode = AgentCollaborationMode.DEFAULT,
    val reasoning: String? = null,
    val plan: String? = null,
    val planProgress: AgentPlanProgress? = null,
    val hookActivities: List<AgentHookActivity> = emptyList(),
    val capabilities: Set<AgentCapability> = emptySet(),
    val invocations: List<AgentInvocation> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    val isStreaming: Boolean = false,
    val shellCommand: String? = null,
    val exitCode: Int? = null,
)

internal data class ConversationGroups(
    val pinned: List<AgentConversationSummary>,
    val recent: List<AgentConversationSummary>,
)

internal fun List<AgentConversationSummary>.groupedByPins(pinnedIds: Set<String>) = ConversationGroups(
    pinned = filter { it.sessionId.value in pinnedIds },
    recent = filterNot { it.sessionId.value in pinnedIds },
)
