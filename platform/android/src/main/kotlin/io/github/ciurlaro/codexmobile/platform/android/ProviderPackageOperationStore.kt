package io.github.ciurlaro.codexmobile.platform.android

import android.app.NotificationManager
import android.content.Context

enum class ProviderPackageOperationKind { INSTALL, REMOVE }

data class ProviderPackageCompletion(
    val kind: ProviderPackageOperationKind,
    val pluginId: String,
    val displayName: String,
    val successful: Boolean,
    val error: String? = null,
) {
    val message: String
        get() = if (successful) {
            "$displayName ${if (kind == ProviderPackageOperationKind.INSTALL) "installed" else "removed"}"
        } else {
            error ?: "$displayName could not be ${if (kind == ProviderPackageOperationKind.INSTALL) "installed" else "removed"}"
        }
}

internal data class ProviderPackageOperation(
    val kind: ProviderPackageOperationKind,
    val pluginId: String,
    val displayName: String,
)

internal object ProviderPackageOperationStore {
    private const val PREFERENCES = "provider-package-operation"
    private const val TOKEN = "token"
    private const val KIND = "kind"
    private const val PLUGIN_ID = "plugin-id"
    private const val DISPLAY_NAME = "display-name"
    private const val COMPLETED = "completed"
    private const val SUCCESSFUL = "successful"
    private const val ERROR = "error"

    @Synchronized
    fun begin(context: Context, token: String, operation: ProviderPackageOperation) {
        check(token.isNotBlank() && operation.pluginId.isNotBlank() && operation.displayName.isNotBlank())
        check(context.preferences().edit()
            .clear()
            .putString(TOKEN, token)
            .putString(KIND, operation.kind.name)
            .putString(PLUGIN_ID, operation.pluginId)
            .putString(DISPLAY_NAME, operation.displayName)
            .putBoolean(COMPLETED, false)
            .commit())
    }

    @Synchronized
    fun complete(context: Context, token: String, result: Result<Unit>): ProviderPackageCompletion? {
        val preferences = context.preferences()
        if (preferences.getString(TOKEN, null) != token) return null
        val operation = preferences.operation() ?: return null
        val error = result.exceptionOrNull()?.message
        check(preferences.edit()
            .putBoolean(COMPLETED, true)
            .putBoolean(SUCCESSFUL, result.isSuccess)
            .putString(ERROR, error)
            .commit())
        return operation.completion(result.isSuccess, error)
    }

    @Synchronized
    fun clear(context: Context, token: String) {
        val preferences = context.preferences()
        if (preferences.getString(TOKEN, null) == token) check(preferences.edit().clear().commit())
    }

    @Synchronized
    fun consume(context: Context): ProviderPackageCompletion? {
        val preferences = context.preferences()
        if (!preferences.getBoolean(COMPLETED, false)) return null
        val operation = preferences.operation() ?: return null
        val completion = operation.completion(
            successful = preferences.getBoolean(SUCCESSFUL, false),
            error = preferences.getString(ERROR, null),
        )
        check(preferences.edit().clear().commit())
        context.getSystemService(NotificationManager::class.java)
            .cancel(ProviderPackageResultReceiver.NOTIFICATION_ID)
        return completion
    }

    private fun Context.preferences() = applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun android.content.SharedPreferences.operation(): ProviderPackageOperation? {
        val kind = getString(KIND, null)?.let { saved ->
            ProviderPackageOperationKind.entries.firstOrNull { it.name == saved }
        } ?: return null
        val pluginId = getString(PLUGIN_ID, null)?.takeIf(String::isNotBlank) ?: return null
        val displayName = getString(DISPLAY_NAME, null)?.takeIf(String::isNotBlank) ?: return null
        return ProviderPackageOperation(kind, pluginId, displayName)
    }

    private fun ProviderPackageOperation.completion(successful: Boolean, error: String?) =
        ProviderPackageCompletion(kind, pluginId, displayName, successful, error)
}
