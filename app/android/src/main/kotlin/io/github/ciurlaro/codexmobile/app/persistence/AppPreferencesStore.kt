package io.github.ciurlaro.codexmobile.app.persistence

import android.content.Context
import android.content.SharedPreferences
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset

internal class AppPreferencesStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(CHAT_PREFERENCES, Context.MODE_PRIVATE)

    val selectedModel: String? get() = preferences.getString(LAST_MODEL, null)
    val selectedEffort: String? get() = preferences.getString(LAST_EFFORT, null)
    val selectedSpeedTier: String? get() = preferences.getString(LAST_SPEED, null)
    val pinnedConversationIds: Set<String>
        get() = preferences.getStringSet(PINNED_CONVERSATIONS, emptySet()).orEmpty().toSet()
    val recentInvocationKeys: List<String>
        get() = preferences.getString(RECENT_INVOCATIONS, null)
            ?.lineSequence()
            ?.filter(String::isNotBlank)
            ?.take(4)
            ?.toList()
            .orEmpty()
    val approvalPreset: AgentApprovalPreset
        get() = preferences.getString(APPROVAL_POLICY, null)
            ?.let { saved -> AgentApprovalPreset.entries.firstOrNull { it.name == saved } }
            ?: AgentApprovalPreset.NEVER
    val savedKnownPluginSourceIds: Set<String>?
        get() = preferences.getStringSet(KNOWN_PLUGIN_SOURCES, null)?.toSet()
    val savedEnabledPluginSourceIds: Set<String>?
        get() = preferences.getStringSet(ENABLED_PLUGIN_SOURCES, null)?.toSet()
    val appWasUpgraded: Boolean
        get() = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                .let { it.lastUpdateTime > it.firstInstallTime }
        }.getOrDefault(false)
    val codexMobilePluginSourceAdded: Boolean
        get() = preferences.getBoolean(CODEX_MOBILE_PLUGIN_SOURCE_ADDED, false)
    val hadAuthenticatedSession: Boolean
        get() = preferences.getBoolean(HAD_AUTHENTICATED_SESSION, false)

    fun saveRecentInvocationKeys(keys: List<String>) {
        preferences.edit().putString(RECENT_INVOCATIONS, keys.joinToString("\n")).apply()
    }

    fun saveSelection(model: String?, effort: String?, speedTier: String?, approval: AgentApprovalPreset) {
        preferences.edit().apply {
            putOrRemove(LAST_MODEL, model)
            putOrRemove(LAST_EFFORT, effort)
            putOrRemove(LAST_SPEED, speedTier)
            putString(APPROVAL_POLICY, approval.name)
        }.apply()
    }

    fun savePinnedConversationIds(ids: Set<String>) {
        preferences.edit().putStringSet(PINNED_CONVERSATIONS, ids).apply()
    }

    fun savePluginSourceSelection(knownIds: Set<String>, enabledIds: Set<String>) {
        preferences.edit()
            .putStringSet(KNOWN_PLUGIN_SOURCES, knownIds)
            .putStringSet(ENABLED_PLUGIN_SOURCES, enabledIds)
            .apply()
    }

    fun setCodexMobilePluginSourceAdded(added: Boolean) {
        preferences.edit().putBoolean(CODEX_MOBILE_PLUGIN_SOURCE_ADDED, added).apply()
    }

    fun setHadAuthenticatedSession(authenticated: Boolean) {
        preferences.edit().putBoolean(HAD_AUTHENTICATED_SESSION, authenticated).apply()
    }

    fun authenticationHandoffPending(): Boolean =
        preferences.getBoolean(AUTHENTICATION_HANDOFF_PENDING, false)

    @Suppress("ApplySharedPref")
    fun setAuthenticationHandoffPending(pending: Boolean) {
        preferences.edit().putBoolean(AUTHENTICATION_HANDOFF_PENDING, pending).commit()
    }

    private companion object {
        const val CHAT_PREFERENCES = "chat-ui"
        const val LAST_MODEL = "last-model"
        const val LAST_EFFORT = "last-effort"
        const val LAST_SPEED = "last-speed"
        const val APPROVAL_POLICY = "approval-policy"
        const val PINNED_CONVERSATIONS = "pinned-conversations"
        const val RECENT_INVOCATIONS = "recent-invocations"
        const val AUTHENTICATION_HANDOFF_PENDING = "authentication-handoff-pending"
        const val KNOWN_PLUGIN_SOURCES = "known-plugin-sources"
        const val ENABLED_PLUGIN_SOURCES = "enabled-plugin-sources"
        const val CODEX_MOBILE_PLUGIN_SOURCE_ADDED = "codex-mobile-plugin-source-added"
        const val HAD_AUTHENTICATED_SESSION = "had-authenticated-session"
    }

}

private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
    if (value == null) remove(key) else putString(key, value)
}
