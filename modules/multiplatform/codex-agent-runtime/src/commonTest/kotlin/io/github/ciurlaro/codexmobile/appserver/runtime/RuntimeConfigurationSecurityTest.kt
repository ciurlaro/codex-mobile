package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RuntimeConfigurationSecurityTest {
    @Test
    fun environmentMergesPlatformFactsWithoutChangingCommonSecurityValues() {
        val environment = buildMinimalRuntimeEnvironment(
            platform = mapOf(
                "PATH" to "/trusted/bin",
                "LANG" to "en_US.UTF-8",
                "LD_LIBRARY_PATH" to "/platform/lib",
            ),
            applicationDirectory = Path("/private/home"),
            temporaryDirectory = Path("/private/tmp"),
            codexHome = Path("/private/codex"),
            certificateBundle = Path("/private/codex/system-ca.pem"),
            proxyUrl = "http://codex:token@127.0.0.1:1234",
        )

        assertEquals("/trusted/bin", environment["PATH"])
        assertEquals("/platform/lib", environment["LD_LIBRARY_PATH"])
        assertEquals("en_US.UTF-8", environment["LANG"])
        assertEquals("http://codex:token@127.0.0.1:1234", environment["HTTPS_PROXY"])
        assertFalse("HTTP_PROXY" in environment)
    }

    @Test
    fun environmentRejectsInvalidAndCommonOwnedPlatformValues() {
        fun build(platform: Map<String, String>) = buildMinimalRuntimeEnvironment(
            platform = platform,
            applicationDirectory = Path("/private/home"),
            temporaryDirectory = Path("/private/tmp"),
            codexHome = Path("/private/codex"),
            certificateBundle = Path("/private/codex/system-ca.pem"),
            proxyUrl = "http://codex:token@127.0.0.1:1234",
        )

        listOf("HOME", "HTTPS_PROXY", "http_proxy", "SSL_CERT_DIR").forEach { name ->
            assertFailsWith<IllegalArgumentException> { build(mapOf(name to "unsafe")) }
        }
        assertFailsWith<IllegalArgumentException> { build(mapOf("BAD=KEY" to "value")) }
        assertFailsWith<IllegalArgumentException> { build(mapOf("PATH" to "bad\u0000value")) }
    }

    @Test
    fun certificatesAndBinaryHashUsePortableFiles() {
        val directory = Path("build", "runtime-configuration-test")
        val codexHome = Path(directory, "codex")
        SystemFileSystem.createDirectories(codexHome)
        val first = Path(directory, "a.pem").also { it.write("first") }
        val second = Path(directory, "b.pem").also { it.write("second") }

        val bundle = prepareRuntimeCertificateBundle(listOf(second, first), codexHome)

        assertEquals("first\nsecond\n", bundle.read())
        val binary = Path(directory, "binary").also { it.write("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            binary.sha256(),
        )
    }

    private fun Path.write(value: String) {
        val output = SystemFileSystem.sink(this).buffered()
        try {
            output.write(value.encodeToByteArray())
        } finally {
            output.close()
        }
    }

    private fun Path.read(): String {
        val input = SystemFileSystem.source(this).buffered()
        return try {
            input.readByteArray().decodeToString(throwOnInvalidSequence = true)
        } finally {
            input.close()
        }
    }
}
