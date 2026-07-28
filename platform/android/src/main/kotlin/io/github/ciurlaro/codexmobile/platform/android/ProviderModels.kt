package io.github.ciurlaro.codexmobile.platform.android

enum class ProviderPackageState { INSTALLING, ACTIVE, REMOVAL_PENDING, REMOVAL_PREPARED, SPLIT_REMOVAL_PENDING }
enum class ProviderDelivery { BUNDLED, LEGACY_SPLIT }

data class InstalledProvider(
    val pluginId: String,
    val providerApi: Int,
    val hostVersionCode: Int,
    val implementationVersion: String,
    val displayName: String,
    val splitNames: List<String>,
    val entryPoint: String,
    val settingsEntryPoint: String?,
    val schemaDigest: String,
    val mcpServerNames: List<String>,
    val pluginName: String,
    val marketplaceName: String,
    val marketplacePath: String?,
    val marketplaceRepository: String?,
    val apkSha256: String,
    val contentSha256: String?,
    val state: ProviderPackageState,
    val message: String? = null,
    val delivery: ProviderDelivery = ProviderDelivery.LEGACY_SPLIT,
)

data class ProviderSettingsEntry(
    val pluginId: String,
    val displayName: String,
    val activityClassName: String?,
    val removalNeedsRetry: Boolean,
    val message: String?,
)
