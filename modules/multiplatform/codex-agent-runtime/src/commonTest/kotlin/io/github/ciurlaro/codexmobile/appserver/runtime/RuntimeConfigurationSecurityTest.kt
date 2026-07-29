package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RuntimeConfigurationSecurityTest {
    @Test
    fun environmentInheritsOnlyAllowedValues() {
        val environment = buildMinimalRuntimeEnvironment(
            inherited = mapOf(
                "PATH" to "/trusted/bin",
                "LANG" to "en_US.UTF-8",
                "SECRET" to "must-not-leak",
                "HTTP_PROXY" to "http://untrusted.example",
            ),
            applicationDirectory = Path("/private/home"),
            temporaryDirectory = Path("/private/tmp"),
            nativeLibraryDirectory = Path("/private/lib"),
            codexHome = Path("/private/codex"),
            certificateBundle = Path("/private/codex/system-ca.pem"),
            proxyUrl = "http://codex:token@127.0.0.1:1234",
        )

        assertEquals("/trusted/bin:/system/bin:/system/xbin", environment["PATH"])
        assertEquals("en_US.UTF-8", environment["LANG"])
        assertEquals("http://codex:token@127.0.0.1:1234", environment["HTTPS_PROXY"])
        assertFalse("SECRET" in environment)
        assertFalse("HTTP_PROXY" in environment)
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
