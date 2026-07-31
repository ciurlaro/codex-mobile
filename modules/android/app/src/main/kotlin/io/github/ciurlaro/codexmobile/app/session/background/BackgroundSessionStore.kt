package io.github.ciurlaro.codexmobile.app.session.background

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BackgroundSessionStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(BACKGROUND_STATE, Context.MODE_PRIVATE)
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()

    fun authorizeStart(): String = UUID.randomUUID().toString().also {
        mutableFailure.value = null
        check(preferences.edit().putString(START_AUTHORIZATION, it).commit()) {
            "Unable to authorize background work"
        }
    }

    @Synchronized
    fun consumeStart(authorization: String?): Boolean {
        if (authorization == null || preferences.getString(START_AUTHORIZATION, null) != authorization) return false
        return preferences.edit().remove(START_AUTHORIZATION).commit()
    }

    @Synchronized
    fun revokeStart(authorization: String) {
        if (preferences.getString(START_AUTHORIZATION, null) == authorization) {
            preferences.edit().remove(START_AUTHORIZATION).commit()
        }
    }

    fun markActive(active: Boolean) {
        preferences.edit().putBoolean(BACKGROUND_ACTIVE, active).commit()
    }

    fun wasActive(): Boolean = preferences.getBoolean(BACKGROUND_ACTIVE, false)

    fun reportFailure(kind: String) {
        mutableFailure.value = "Android could not start background work ($kind)"
    }

    private companion object {
        const val BACKGROUND_STATE = "background-state"
        const val BACKGROUND_ACTIVE = "active"
        const val START_AUTHORIZATION = "start-authorization"
    }
}
