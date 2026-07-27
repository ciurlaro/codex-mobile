package io.github.ciurlaro.codexmobile.core

import kotlinx.coroutines.flow.Flow

interface AgentClient : AutoCloseable {
    val events: Flow<AgentEvent>

    suspend fun authenticate()

    suspend fun cancelAuthentication()

    suspend fun signOut()

    suspend fun listModels(): List<AgentModel>

    suspend fun listSkills(workingDirectory: String, forceReload: Boolean = false): AgentSkillCatalog

    suspend fun readSkill(path: String, offset: Long = 0): AgentSkillChunk

    suspend fun setSkillEnabled(path: String, enabled: Boolean)

    suspend fun listInstalledPlugins(
        workingDirectory: String?,
        forceRefresh: Boolean = false,
    ): AgentPluginCatalog

    suspend fun listAvailablePlugins(
        workingDirectory: String?,
        forceRefresh: Boolean = false,
    ): AgentPluginCatalog

    suspend fun addPluginMarketplace(source: String) {
        error("Plugin marketplace sources are unavailable")
    }

    suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail

    suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult

    suspend fun uninstallPlugin(plugin: AgentPluginReference): AgentPluginRemovalResult

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean)

    suspend fun listConnectors(sessionId: SessionId? = null, forceReload: Boolean = false): List<AgentConnector>

    suspend fun listMcpServers(): List<AgentMcpServer>

    suspend fun startMcpOauth(serverName: String, sessionId: SessionId? = null): String

    suspend fun listSessions(): List<AgentConversationSummary>

    suspend fun readSession(sessionId: SessionId): AgentConversation

    suspend fun renameSession(sessionId: SessionId, name: String)

    suspend fun deleteSession(sessionId: SessionId)

    suspend fun openSession(
        previous: SessionId? = null,
        settings: AgentRuntimeSettings = AgentRuntimeSettings(),
    ): SessionId

    suspend fun sendTurn(sessionId: SessionId, request: AgentTurnRequest)

    suspend fun runShellCommand(sessionId: SessionId, command: String)

    suspend fun cancelTurn(sessionId: SessionId)

    suspend fun resolveApproval(requestId: String, decision: AgentApprovalDecision)

    suspend fun resolveElicitation(requestId: String, response: AgentElicitationResponse)
}

@JvmInline
value class SessionId(val value: String)

data class AgentModel(
    val id: String,
    val displayName: String,
    val description: String,
    val supportedEfforts: List<String>,
    val defaultEffort: String,
    val isDefault: Boolean,
    val serviceTiers: List<AgentServiceTier> = emptyList(),
    val defaultServiceTier: String? = null,
)

data class AgentServiceTier(
    val id: String,
    val name: String,
    val description: String,
)

enum class AgentApprovalPreset(
    val displayName: String,
    val approvalPolicy: String,
    val approvalsReviewer: String,
) {
    NEVER("Never", "never", "user"),
    AUTO_REVIEW("Auto review", "on-request", "auto_review"),
    ASK_ME("Ask me", "on-request", "user"),
    STRICT("Strict", "untrusted", "user"),
}

enum class AgentApprovalDecision {
    ACCEPT,
    DECLINE,
}

data class AgentRuntimeSettings(
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.AUTO_REVIEW,
    val serviceTier: String? = null,
    val workingDirectory: String? = null,
)

data class AgentConversationSummary(
    val sessionId: SessionId,
    val title: String,
    val updatedAtEpochSeconds: Long,
)

data class AgentConversation(
    val summary: AgentConversationSummary,
    val messages: List<AgentMessage>,
)

enum class AgentMessageRole { USER, CODEX }

data class AgentMessage(
    val id: String,
    val clientId: String?,
    val role: AgentMessageRole,
    val text: String,
    val reasoning: String? = null,
    val shellCommand: String? = null,
    val exitCode: Int? = null,
    val capabilities: Set<AgentCapability> = emptySet(),
    val invocations: List<AgentInvocation> = emptyList(),
)

enum class AgentCapability(
    val id: String,
    val displayLabel: String,
    val icon: String?,
    val promptLabel: String,
) {
    WEB_SEARCH("web_search", "Web search", "🌐", "Use 🌐 Web search"),
}

data class AgentTurnRequest(
    val prompt: String,
    val clientMessageId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val serviceTier: String? = null,
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.AUTO_REVIEW,
    val capabilities: Set<AgentCapability> = emptySet(),
    val invocations: List<AgentInvocation> = emptyList(),
    val workingDirectory: String? = null,
)

fun deriveConversationTitle(
    explicitName: String?,
    firstUserText: String,
    maxLength: Int = 80,
): String {
    require(maxLength > 0)
    val title = explicitName?.trim()?.takeIf { it.isNotEmpty() }
        ?: firstUserText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: "New chat"
    return title.take(maxLength).trimEnd()
}

sealed interface AgentEvent {
    data class AuthenticationRequired(
        val signInUrl: String,
    ) : AgentEvent {
        override fun toString(): String = "AuthenticationRequired"
    }

    data object Authenticated : AgentEvent

    data class SessionOpened(
        val sessionId: SessionId,
        val model: String? = null,
        val effort: String? = null,
        val serviceTier: String? = null,
    ) : AgentEvent

    data class TextDelta(
        val sessionId: SessionId,
        val text: String,
        val itemId: String? = null,
    ) : AgentEvent

    data class ReasoningSummaryDelta(
        val sessionId: SessionId,
        val text: String,
        val itemId: String,
        val summaryIndex: Long,
    ) : AgentEvent

    data class ShellOutputDelta(
        val sessionId: SessionId,
        val text: String,
    ) : AgentEvent

    data class ShellCommandCompleted(
        val sessionId: SessionId,
        val exitCode: Int?,
    ) : AgentEvent

    data class ApprovalRequested(
        val sessionId: SessionId,
        val requestId: String,
        val title: String,
        val details: String,
    ) : AgentEvent

    data class WorkActivityChanged(
        val sessionId: SessionId,
        val activity: AgentWorkActivity?,
    ) : AgentEvent

    data object SkillsChanged : AgentEvent

    data object PluginsChanged : AgentEvent

    data object ConnectorsChanged : AgentEvent

    data class McpOauthCompleted(
        val serverName: String,
        val success: Boolean,
        val error: String? = null,
    ) : AgentEvent

    data class ElicitationRequested(val elicitation: AgentElicitation) : AgentEvent

    data class TurnCompleted(val sessionId: SessionId) : AgentEvent

    data class Failure(
        val sessionId: SessionId?,
        val code: String,
        val message: String,
        val recoverable: Boolean,
    ) : AgentEvent
}

enum class AgentWorkActivity {
    RUNNING_COMMAND,
    WRITING_FILES,
}
