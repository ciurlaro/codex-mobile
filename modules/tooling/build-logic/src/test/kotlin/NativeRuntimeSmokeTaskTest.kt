import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class NativeRuntimeSmokeTaskTest {
    @Test
    fun `completes two initialize and SQLite lifecycle passes`() {
        val root = createTempDirectory("native-handshake").toFile()
        try {
            val runtime = """
                #!/bin/sh
                read request
                printf '{"id":1,"result":{"codexHome":"%s","platformFamily":"unix","platformOs":"linux"}}\n' "${'$'}CODEX_HOME"
                read initialized
                printf 'SQLite format 3\000' > "${'$'}CODEX_HOME/logs_2.sqlite"
            """.trimIndent().toByteArray()
            val task = task(root, runtime)
            task.smoke()
            assertFalse(root.resolve("build/tmp/nativeSmokeTest/native-smoke").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `times out and kills the native process tree`() {
        val root = createTempDirectory("native-timeout").toFile()
        try {
            val pid = root.resolve("pid")
            val runtime = """
                #!/bin/sh
                printf '%s' "${'$'}${'$'}" > '${pid.absolutePath}'
                sleep 10
            """.trimIndent().toByteArray()
            val task = task(root, runtime).apply {
                ioTimeoutMillis.set(100L)
                processTimeoutMillis.set(100L)
            }
            assertFailsWith<java.util.concurrent.TimeoutException> { task.smoke() }
            val handle = pid.takeIf { it.isFile }?.readText()?.toLongOrNull()
                ?.let { ProcessHandle.of(it).orElse(null) }
            if (handle != null) {
                repeat(20) { if (handle.isAlive) Thread.sleep(25) }
                assertFalse(handle.isAlive)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun task(root: java.io.File, runtime: ByteArray): NativeRuntimeSmokeTask {
        val artifacts = root.resolve("artifacts").also { it.mkdirs() }
        val apk = artifacts.resolve(CodexMobileAutomation.Artifacts.DEBUG_APK)
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/${CodexMobileAutomation.App.ABI}/${CodexMobileAutomation.App.RUNTIME_LIBRARY}"))
            zip.write(runtime)
            zip.closeEntry()
        }
        val certificate = root.resolve("certificates").apply { writeText("test") }
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        return project.tasks.create("nativeSmokeTest", NativeRuntimeSmokeTask::class.java).apply {
            artifactsDirectory.set(artifacts)
            expectedBinarySha256.set(runtime.sha256())
            certificateFile.set(certificate)
        }
    }

    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
