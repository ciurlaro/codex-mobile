package io.github.ciurlaro.codexmobile.app.persistence

import android.content.Context
import android.content.SharedPreferences
import io.github.ciurlaro.codexmobile.app.presentation.model.CustomExtensionSource
import io.github.ciurlaro.codexmobile.core.AgentApprovalPreset
import org.json.JSONArray
import org.json.JSONObject

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
            ?: AgentApprovalPreset.AUTO_REVIEW
    val savedKnownExtensionSourceIds: Set<String>?
        get() = preferences.getStringSet(KNOWN_PLUGIN_SOURCES, null)?.toSet()
    val savedEnabledExtensionSourceIds: Set<String>?
        get() = preferences.getStringSet(ENABLED_PLUGIN_SOURCES, null)?.toSet()
    val savedCustomExtensionSources: List<CustomExtensionSource>
        get() = preferences.getString(CUSTOM_EXTENSION_SOURCES, null)?.let(::decodeCustomExtensionSources).orEmpty()
    val appWasUpgraded: Boolean
        get() = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                .let { it.lastUpdateTime > it.firstInstallTime }
        }.getOrDefault(false)
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

    fun saveExtensionSourceSelection(
        knownIds: Set<String>,
        enabledIds: Set<String>,
        customSources: List<CustomExtensionSource>,
    ) {
        preferences.edit()
            .putStringSet(KNOWN_PLUGIN_SOURCES, knownIds)
            .putStringSet(ENABLED_PLUGIN_SOURCES, enabledIds)
            .putString(CUSTOM_EXTENSION_SOURCES, encodeCustomExtensionSources(customSources))
            .apply()
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
        const val CUSTOM_EXTENSION_SOURCES = "custom-extension-sources"
        const val HAD_AUTHENTICATED_SESSION = "had-authenticated-session"
    }

}

private fun encodeCustomExtensionSources(sources: List<CustomExtensionSource>): String = JSONArray().apply {
    sources.distinctBy(CustomExtensionSource::id).forEach { source ->
        put(
            JSONObject()
                .put("id", source.id)
                .put("url", source.url)
                .put("marketplaceName", source.marketplaceName)
                .put("skills", source.supportsSkills)
                .put("plugins", source.supportsPlugins),
        )
    }
}.toString()

private fun decodeCustomExtensionSources(value: String): List<CustomExtensionSource> = runCatching {
    val array = JSONArray(value)
    buildList {
        repeat(array.length()) { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val url = item.getString("url")
            if (id.isNotBlank() && url.isNotBlank()) {
                add(
                    CustomExtensionSource(
                        id = id,
                        url = url,
                        marketplaceName = item.optString("marketplaceName").takeIf(String::isNotBlank),
                        supportsSkills = item.optBoolean("skills"),
                        supportsPlugins = item.optBoolean("plugins"),
                    ),
                )
            }
        }
    }
}.getOrDefault(emptyList())

private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
    if (value == null) remove(key) else putString(key, value)
}
