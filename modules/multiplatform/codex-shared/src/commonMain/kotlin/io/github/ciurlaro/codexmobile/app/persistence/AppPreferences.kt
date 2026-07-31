package io.github.ciurlaro.codexmobile.app.persistence

import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset

data class AppPreferenceState(
    val selectedModel: String? = null,
    val selectedEffort: String? = null,
    val selectedSpeedTier: String? = null,
    val pinnedConversationIds: Set<String> = emptySet(),
    val recentInvocationKeys: List<String> = emptyList(),
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.AUTO_REVIEW,
    val pendingPluginSetups: Map<String, Set<String>> = emptyMap(),
    val hadAuthenticatedSession: Boolean = false,
    val authenticationHandoffPending: Boolean = false,
)

interface AppPreferences {
    suspend fun load(): AppPreferenceState
    suspend fun saveRecentInvocationKeys(keys: List<String>)
    suspend fun saveSelection(
        model: String?,
        effort: String?,
        speedTier: String?,
        approval: AgentApprovalPreset,
    )

    suspend fun savePinnedConversationIds(ids: Set<String>)
    suspend fun setHadAuthenticatedSession(authenticated: Boolean)
    suspend fun setAuthenticationHandoffPending(pending: Boolean)
    suspend fun savePendingPluginSetups(setups: Map<String, Set<String>>)
}
