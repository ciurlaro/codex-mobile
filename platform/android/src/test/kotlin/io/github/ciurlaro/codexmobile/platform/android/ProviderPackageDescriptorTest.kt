package io.github.ciurlaro.codexmobile.platform.android

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
    }

    @Test
    fun `invalid package checksum is rejected`() {
        assertFailsWith<IllegalArgumentException> { ProviderPackageDescriptor.parse(manifest("unknown")) }
    }

    @Test
    fun `unknown manifest fields are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderPackageDescriptor.parse(manifest("a".repeat(64)).replaceFirst("{", "{\n\"command\": \"no\","))
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
              "url": "https://github.com/owner/repository/releases/download/v1/provider_sample.apk",
              "sha256": "$checksum"
            }
          }
        }
    """.trimIndent()
}
