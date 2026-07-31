import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReleaseArchiveTest {
    @Test
    fun `validates exact APK and bundle native layouts`() {
        val root = createTempDirectory("release-archives").toFile()
        try {
            val runtime = "runtime".toByteArray()
            val apk = root.resolve("release.apk")
            zip(apk, CodexMobileAutomation.App.apkReleaseNativePaths.associateWith {
                if (it.endsWith(CodexMobileAutomation.App.RUNTIME_LIBRARY)) runtime else byteArrayOf(1)
            })
            verifyReleaseArchive(
                apk, CodexMobileAutomation.App.apkReleaseNativePaths, "", runtime.sha256(),
            )

            val bundle = root.resolve("release.aab")
            zip(
                bundle,
                CodexMobileAutomation.App.bundleReleaseNativePaths.associateWith { byteArrayOf(1) } +
                    ("base/manifest/AndroidManifest.xml" to byteArrayOf(2)),
            )
            verifyReleaseArchive(
                bundle, CodexMobileAutomation.App.bundleReleaseNativePaths, "base/", null,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects forbidden ABI and runtime hash drift`() {
        val root = createTempDirectory("release-archive-failure").toFile()
        try {
            val runtime = "runtime".toByteArray()
            val forbidden = root.resolve("forbidden.apk")
            zip(
                forbidden,
                CodexMobileAutomation.App.apkReleaseNativePaths.associateWith { runtime } +
                    ("lib/x86_64/forbidden.so" to byteArrayOf(1)),
            )
            val abiFailure = assertFailsWith<IllegalStateException> {
                verifyReleaseArchive(forbidden, CodexMobileAutomation.App.apkReleaseNativePaths, "", runtime.sha256())
            }
            assertTrue("forbidden ABI" in abiFailure.message!!)

            val valid = root.resolve("hash.apk")
            zip(valid, CodexMobileAutomation.App.apkReleaseNativePaths.associateWith { runtime })
            val hashFailure = assertFailsWith<IllegalStateException> {
                verifyReleaseArchive(valid, CodexMobileAutomation.App.apkReleaseNativePaths, "", "0".repeat(64))
            }
            assertTrue("runtime hash mismatch" in hashFailure.message!!)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zip(target: java.io.File, entries: Map<String, ByteArray>) {
        ZipOutputStream(target.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
