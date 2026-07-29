package io.github.ciurlaro.codexmobile.extension.host

import android.content.Context
import android.os.Build
import io.github.ciurlaro.codexmobile.agent.PluginProviderHost
import io.github.ciurlaro.codexmobile.agent.ProviderInstallDisposition
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.provider.api.ProviderContext
import io.github.ciurlaro.codexmobile.provider.api.ProviderDescriptor
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderRemovalState
import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun validateBundledProvider(
    addOn: ProviderPackageDescriptor,
    bundled: ProviderDescriptor,
    bundledEntryPoint: String,
    mcpServerNames: Set<String>,
    hostVersion: Int,
    supportedAbis: Set<String> = Build.SUPPORTED_ABIS.toSet(),
) {
    check(addOn.pluginId == bundled.pluginId) { "Provider plugin ID does not match its bundled code" }
    check(addOn.mcpServerNames.toSet() == mcpServerNames) {
        "Provider MCP configuration does not match its add-on metadata"
    }
    check(bundled.providerApi in addOn.minProviderApi..addOn.maxProviderApi) {
        "This provider requires an unsupported host API"
    }
    check(addOn.hostVersionCode == hostVersion && hostVersion in bundled.minHostVersionCode..bundled.maxHostVersionCode) {
        "This provider was built for another Codex Mobile version"
    }
    check(supportedAbis.any(addOn.abis::contains)) { "This provider does not support this device ABI" }
    check(addOn.implementationVersion == bundled.implementationVersion)
    check(addOn.displayName == bundled.displayName)
    check(addOn.schemaDigest == bundled.schemaDigest)
    check(addOn.entryPoint == bundledEntryPoint)
    check(addOn.settingsEntryPoint == bundled.settingsEntryPoint)
}

internal data class ProviderPackageInfo(
    val descriptor: ProviderPackageDescriptor,
    val marketplaceRepository: String,
)

internal data class ProviderPackageDescriptor(
    val minProviderApi: Int,
    val maxProviderApi: Int,
    val hostVersionCode: Int,
    val pluginId: String,
    val implementationVersion: String,
    val displayName: String,
    val schemaDigest: String,
    val mcpServerNames: List<String>,
    val splitNames: List<String>,
    val entryPoint: String,
    val settingsEntryPoint: String?,
    val abis: List<String>,
    val apkUri: URI,
    val sha256: String,
) {
    fun toInstalledProvider(
        plugin: AgentPluginReference,
        providerApi: Int,
        marketplaceRepository: String,
    ) = InstalledProvider(
        pluginId = pluginId,
        providerApi = providerApi,
        hostVersionCode = hostVersionCode,
        implementationVersion = implementationVersion,
        displayName = displayName,
        delivery = ProviderDelivery.BUNDLED,
        splitNames = emptyList(),
        entryPoint = entryPoint,
        settingsEntryPoint = settingsEntryPoint,
        schemaDigest = schemaDigest,
        mcpServerNames = mcpServerNames,
        pluginName = plugin.name,
        marketplaceName = plugin.marketplaceName,
        marketplacePath = plugin.marketplacePath,
        marketplaceRepository = marketplaceRepository,
        apkSha256 = sha256,
        contentSha256 = null,
        state = ProviderPackageState.INSTALLING,
    )

    companion object {
        fun parse(value: String): ProviderPackageDescriptor {
            val root = Json.parseToJsonElement(value).jsonObject
            root.requireOnly(
                "formatVersion", "providerApi", "host", "pluginId", "implementationVersion",
                "displayName", "schemaDigest", "mcpServerNames", "android",
            )
            check(root.getValue("formatVersion").jsonPrimitive.int == 1) { "Unsupported provider manifest" }
            val providerApi = root.getValue("providerApi").jsonObject
            val host = root.getValue("host").jsonObject
            val android = root.getValue("android").jsonObject
            val packageInfo = android.getValue("package").jsonObject
            providerApi.requireOnly("min", "max")
            host.requireOnly("versionCode")
            android.requireOnly("splitNames", "entryPoint", "settingsEntryPoint", "abis", "package")
            packageInfo.requireOnly("url", "sha256")
            val checksum = packageInfo.getValue("sha256").jsonPrimitive.content.lowercase()
            require(checksum.matches(Regex("[a-f0-9]{64}"))) { "Invalid provider APK checksum" }
            val schemaDigest = root.getValue("schemaDigest").jsonPrimitive.content.lowercase()
            require(schemaDigest.matches(Regex("[a-f0-9]{64}"))) { "Invalid provider schema digest" }
            return ProviderPackageDescriptor(
                minProviderApi = providerApi.getValue("min").jsonPrimitive.int,
                maxProviderApi = providerApi.getValue("max").jsonPrimitive.int,
                hostVersionCode = host.getValue("versionCode").jsonPrimitive.int,
                pluginId = root.getValue("pluginId").jsonPrimitive.content,
                implementationVersion = root.getValue("implementationVersion").jsonPrimitive.content,
                displayName = root.getValue("displayName").jsonPrimitive.content,
                schemaDigest = schemaDigest,
                mcpServerNames = root.getValue("mcpServerNames").jsonArray.map { it.jsonPrimitive.content },
                splitNames = android.getValue("splitNames").jsonArray.map { it.jsonPrimitive.content },
                entryPoint = android.getValue("entryPoint").jsonPrimitive.content,
                settingsEntryPoint = android["settingsEntryPoint"]?.jsonPrimitive?.content,
                abis = android.getValue("abis").jsonArray.map { it.jsonPrimitive.content },
                apkUri = URI(packageInfo.getValue("url").jsonPrimitive.content),
                sha256 = checksum,
            ).also {
                require(it.minProviderApi > 0 && it.maxProviderApi >= it.minProviderApi) { "Invalid provider API range" }
                require(it.hostVersionCode > 0) { "Invalid host version" }
                require(it.pluginId.matches(Regex("[a-z0-9-]+@[a-z0-9-]+"))) { "Invalid provider plugin ID" }
                require(it.displayName.isNotBlank()) { "Invalid provider display name" }
                require(it.splitNames.size == 1 && it.splitNames.all { split -> split.matches(Regex("[a-z][a-z0-9_]{0,79}")) }) {
                    "Invalid provider split names"
                }
                require(it.entryPoint.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]{1,299}"))) { "Invalid provider entry point" }
                require(it.mcpServerNames.isNotEmpty() && it.mcpServerNames.distinct().size == it.mcpServerNames.size &&
                    it.mcpServerNames.all { name -> name.matches(Regex("[A-Za-z0-9_-]{1,64}")) }) {
                    "Invalid provider MCP server names"
                }
                require(it.settingsEntryPoint == null || it.settingsEntryPoint.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]{1,299}"))) {
                    "Invalid provider settings entry point"
                }
                require(it.abis.isNotEmpty() && it.abis.all { abi -> abi in SUPPORTED_ABIS }) { "Invalid provider ABIs" }
                ProviderSourcePolicy.requireProviderUri(it.apkUri, redirected = false)
            }
        }
    }
}
