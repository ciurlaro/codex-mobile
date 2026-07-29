package io.github.ciurlaro.codexmobile.extension.host

import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.provider.api.ProviderDescriptor
import io.github.ciurlaro.codexmobile.provider.api.ProviderToolDefinition
import java.io.File
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.buildJsonObject

class ProviderPackageDescriptorTest {
    @Test
    fun `provider package manifest is strict and typed`() {
        val descriptor = ProviderPackageDescriptor.parse(manifest("a".repeat(64)))
        assertEquals("sample@catalog", descriptor.pluginId)
        assertEquals(listOf("provider_sample"), descriptor.splitNames)
        assertEquals(listOf("sample-provider"), descriptor.mcpServerNames)
        assertEquals("a".repeat(64), descriptor.sha256)
        assertEquals(
            descriptor.sha256,
            descriptor.toInstalledProvider(
                AgentPluginReference("sample@catalog", "sample", "catalog"),
                providerApi = 2,
                marketplaceRepository = CANONICAL_PROVIDER_REPOSITORY,
            ).apkSha256,
        )
    }

    @Test
    fun `invalid package checksum is rejected`() {
        assertFailsWith<IllegalArgumentException> { ProviderPackageDescriptor.parse(manifest("unknown")) }
    }

    @Test
    fun `bundled provider must match signed marketplace metadata`() {
        val addOn = ProviderPackageDescriptor.parse(manifest("a".repeat(64)))
        val bundled = ProviderDescriptor(
            pluginId = addOn.pluginId,
            implementationVersion = addOn.implementationVersion,
            tools = listOf(ProviderToolDefinition(addOn.pluginId, "sample", "Sample", buildJsonObject {})),
            providerApi = 2,
            minHostVersionCode = 3,
            maxHostVersionCode = 3,
            displayName = addOn.displayName,
            settingsEntryPoint = addOn.settingsEntryPoint,
            schemaDigest = addOn.schemaDigest,
        )

        validateBundledProvider(
            addOn,
            bundled,
            addOn.entryPoint,
            addOn.mcpServerNames.toSet(),
            hostVersion = 3,
            supportedAbis = setOf("arm64-v8a"),
        )
        assertFailsWith<IllegalStateException> {
            validateBundledProvider(
                addOn.copy(schemaDigest = "c".repeat(64)),
                bundled,
                addOn.entryPoint,
                addOn.mcpServerNames.toSet(),
                hostVersion = 3,
                supportedAbis = setOf("arm64-v8a"),
            )
        }
    }

    @Test
    fun `unknown manifest fields are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderPackageDescriptor.parse(manifest("a".repeat(64)).replaceFirst("{", "{\n\"command\": \"no\","))
        }
    }

    @Test
    fun `canonical provider origin is read from app server checkout`() {
        withCheckout("https://github.com/ciurlaro/codex-mobile-plugins.git") { root, manifest ->
            assertEquals(
                "ciurlaro/codex-mobile-plugins",
                ProviderSourcePolicy.marketplaceRepository(manifest, root),
            )
        }
    }

    @Test
    fun `github ssh origins normalize but forks remain untrusted`() {
        assertEquals(
            "ciurlaro/codex-mobile-plugins",
            ProviderSourcePolicy.normalizeGitHubRepository("git@github.com:Ciurlaro/Codex-Mobile-Plugins.git"),
        )
        assertFailsWith<IllegalArgumentException> {
            ProviderSourcePolicy.requireCanonicalRepository("someone/codex-mobile-plugins")
        }
    }

    @Test
    fun `missing origin and paths outside app storage fail closed`() {
        val root = createTempDirectory("codex-root").toFile()
        val outside = createTempDirectory("outside-marketplace").toFile()
        try {
            val inside = File(root, "plugins/documents/codex-mobile-addon.json").apply {
                checkNotNull(parentFile).mkdirs()
                writeText("{}")
            }
            assertFailsWith<IllegalStateException> {
                ProviderSourcePolicy.marketplaceRepository(inside, root)
            }
            val escaped = File(outside, "codex-mobile-addon.json").apply { writeText("{}") }
            assertFailsWith<IllegalArgumentException> {
                ProviderSourcePolicy.marketplaceRepository(escaped, root)
            }
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `provider downloads stay on canonical github releases`() {
        ProviderSourcePolicy.requireProviderUri(
            URI("https://github.com/ciurlaro/codex-mobile-plugins/releases/download/v1/provider.apk"),
            redirected = false,
        )
        ProviderSourcePolicy.requireProviderUri(
            URI("https://release-assets.githubusercontent.com/github-production-release-asset/123/provider.apk?token=x"),
            redirected = true,
        )
        assertFailsWith<IllegalArgumentException> {
            ProviderSourcePolicy.requireProviderUri(
                URI("https://github.com/someone/codex-mobile-plugins/releases/download/v1/provider.apk"),
                redirected = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderSourcePolicy.requireProviderUri(
                URI("https://attacker.githubusercontent.com/provider.apk"),
                redirected = true,
            )
        }
    }

    private fun manifest(checksum: String) = """
        {
          "formatVersion": 1,
          "providerApi": { "min": 2, "max": 2 },
          "host": { "versionCode": 3 },
          "pluginId": "sample@catalog",
          "implementationVersion": "1.0.0",
          "displayName": "Sample",
          "schemaDigest": "${"b".repeat(64)}",
          "mcpServerNames": ["sample-provider"],
          "android": {
            "splitNames": ["provider_sample"],
            "entryPoint": "example.SampleProvider",
            "settingsEntryPoint": "example.SampleSettingsActivity",
            "abis": ["arm64-v8a"],
            "package": {
              "url": "https://github.com/ciurlaro/codex-mobile-plugins/releases/download/v1/provider_sample.apk",
              "sha256": "$checksum"
            }
          }
        }
    """.trimIndent()

    private fun withCheckout(origin: String, block: (File, File) -> Unit) {
        val root = createTempDirectory("provider-marketplace").toFile()
        try {
            File(root, ".git/config").apply {
                checkNotNull(parentFile).mkdirs()
                writeText("[remote \"origin\"]\n\turl = $origin\n")
            }
            val manifest = File(root, "plugins/documents/codex-mobile-addon.json").apply {
                checkNotNull(parentFile).mkdirs()
                writeText("{}")
            }
            block(root, manifest)
        } finally {
            root.deleteRecursively()
        }
    }

}
