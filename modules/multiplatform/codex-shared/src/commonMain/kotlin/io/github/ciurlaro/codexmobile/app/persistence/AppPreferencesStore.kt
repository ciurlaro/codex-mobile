package io.github.ciurlaro.codexmobile.app.persistence

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.IOException
import okio.Path

class AppPreferencesStore(
    path: Path,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AppPreferences {
    private val dataStore = PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { path },
    )

    override suspend fun load(): AppPreferenceState = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .first()
        .toAppPreferenceState()

    override suspend fun saveRecentInvocationKeys(keys: List<String>) {
        dataStore.edit { it[RECENT_INVOCATIONS] = keys.filter(String::isNotBlank).take(4).joinToString("\n") }
    }

    override suspend fun saveSelection(
        model: String?,
        effort: String?,
        speedTier: String?,
        approval: AgentApprovalPreset,
    ) {
        dataStore.edit {
            it.putOrRemove(LAST_MODEL, model)
            it.putOrRemove(LAST_EFFORT, effort)
            it.putOrRemove(LAST_SPEED, speedTier)
            it[APPROVAL_POLICY] = approval.name
        }
    }

    override suspend fun savePinnedConversationIds(ids: Set<String>) {
        dataStore.edit { it[PINNED_CONVERSATIONS] = ids.filter(String::isNotBlank).toSet() }
    }

    override suspend fun setHadAuthenticatedSession(authenticated: Boolean) {
        dataStore.edit { it[HAD_AUTHENTICATED_SESSION] = authenticated }
    }

    override suspend fun setAuthenticationHandoffPending(pending: Boolean) {
        dataStore.edit { it[AUTHENTICATION_HANDOFF_PENDING] = pending }
    }

    override suspend fun savePendingPluginSetups(setups: Map<String, Set<String>>) {
        dataStore.edit { it[PENDING_PLUGIN_SETUPS] = encodePendingPluginSetups(setups) }
    }
}

private fun Preferences.toAppPreferenceState() = AppPreferenceState(
    selectedModel = this[LAST_MODEL],
    selectedEffort = this[LAST_EFFORT],
    selectedSpeedTier = this[LAST_SPEED],
    pinnedConversationIds = this[PINNED_CONVERSATIONS].orEmpty().filter(String::isNotBlank).toSet(),
    recentInvocationKeys = this[RECENT_INVOCATIONS]
        ?.lineSequence()
        ?.filter(String::isNotBlank)
        ?.take(4)
        ?.toList()
        .orEmpty(),
    approvalPreset = this[APPROVAL_POLICY]
        ?.let { saved -> AgentApprovalPreset.entries.firstOrNull { it.name == saved } }
        ?: AgentApprovalPreset.AUTO_REVIEW,
    pendingPluginSetups = this[PENDING_PLUGIN_SETUPS]?.let(::decodePendingPluginSetups).orEmpty(),
    hadAuthenticatedSession = this[HAD_AUTHENTICATED_SESSION] ?: false,
    authenticationHandoffPending = this[AUTHENTICATION_HANDOFF_PENDING] ?: false,
)

private fun encodePendingPluginSetups(setups: Map<String, Set<String>>): String {
    val normalized: Map<String, Set<String>> = buildMap {
        setups.toSortedMap().forEach { (pluginId, ids) ->
            val connectorIds = ids.filter(String::isNotBlank).sorted().toSet()
            if (pluginId.isNotBlank() && connectorIds.isNotEmpty()) put(pluginId, connectorIds)
        }
    }
    return Json.encodeToString<Map<String, Set<String>>>(normalized)
}

private fun decodePendingPluginSetups(value: String): Map<String, Set<String>> = runCatching {
    Json.decodeFromString<Map<String, Set<String>>>(value)
        .filterKeys(String::isNotBlank)
        .mapValues { (_, ids) -> ids.filter(String::isNotBlank).toSet() }
        .filterValues(Set<String>::isNotEmpty)
}.getOrDefault(emptyMap())

private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
    if (value == null) remove(key) else this[key] = value
}

private val LAST_MODEL = stringPreferencesKey("last-model")
private val LAST_EFFORT = stringPreferencesKey("last-effort")
private val LAST_SPEED = stringPreferencesKey("last-speed")
private val APPROVAL_POLICY = stringPreferencesKey("approval-policy")
private val PINNED_CONVERSATIONS = stringSetPreferencesKey("pinned-conversations")
private val RECENT_INVOCATIONS = stringPreferencesKey("recent-invocations")
private val AUTHENTICATION_HANDOFF_PENDING = booleanPreferencesKey("authentication-handoff-pending")
private val HAD_AUTHENTICATED_SESSION = booleanPreferencesKey("had-authenticated-session")
private val PENDING_PLUGIN_SETUPS = stringPreferencesKey("pending-plugin-setups")
