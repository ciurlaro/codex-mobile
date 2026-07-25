package io.github.ciurlaro.codexmobile.platform.android

import io.github.ciurlaro.codexmobile.core.AgentPluginReference
import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `installed content identity ignores signatures stripped by Android`() {
        val signed = providerApk(
            "classes.dex" to "provider",
            "META-INF/MANIFEST.MF" to "manifest",
            "META-INF/CODEX-MO.SF" to "signature",
            "META-INF/CODEX-MO.RSA" to "certificate",
        )
        val installed = providerApk("classes.dex" to "provider")
        val changed = providerApk("classes.dex" to "different")
        try {
            assertEquals(signed.apkContentSha256(), installed.apkContentSha256())
            kotlin.test.assertNotEquals(signed.apkContentSha256(), changed.apkContentSha256())
        } finally {
            signed.delete()
            installed.delete()
            changed.delete()
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
                parentFile.mkdirs()
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
                parentFile.mkdirs()
                writeText("[remote \"origin\"]\n\turl = $origin\n")
            }
            val manifest = File(root, "plugins/documents/codex-mobile-addon.json").apply {
                parentFile.mkdirs()
                writeText("{}")
            }
            block(root, manifest)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun providerApk(vararg entries: Pair<String, String>) = createTempFile("provider", ".apk").toFile().apply {
        outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }
}
