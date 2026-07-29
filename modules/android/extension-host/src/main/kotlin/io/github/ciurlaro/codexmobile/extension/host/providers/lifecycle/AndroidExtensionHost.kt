package io.github.ciurlaro.codexmobile.extension.host

import android.content.Context
import android.content.Intent
import io.github.ciurlaro.codexmobile.agent.BuiltInToolDispatcher

class AndroidExtensionHost(
    context: Context,
    resolveWorkspaceFile: (workspace: String, path: String, mustExist: Boolean) -> String,
) {
    private val appContext = context.applicationContext
    private val providers = AndroidProviderRegistry(appContext, resolveWorkspaceFile)

    val builtInToolDispatcher: BuiltInToolDispatcher = providers.dispatcher
    val providerPackages = AndroidProviderPackageManager(appContext, providers)
    val pluginMarketplaces = AndroidPluginMarketplaceManager(appContext)
    val skillPackages = AndroidSkillPackageManager(appContext)

    fun providerSettings(): List<ProviderSettingsEntry> = providers.settings()

    fun openProviderSettings(pluginId: String) {
        val entry = providers.settings().singleOrNull { it.pluginId == pluginId }
            ?: error("Provider settings are unavailable")
        val activityClassName = checkNotNull(entry.activityClassName) { "Provider settings are unavailable" }
        appContext.startActivity(
            Intent().setClassName(appContext, activityClassName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    suspend fun finishProviderRemoval(pluginId: String) = providerPackages.remove(pluginId)
}
