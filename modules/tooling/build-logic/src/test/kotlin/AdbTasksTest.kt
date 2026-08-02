import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class AdbTasksTest {
    @Test
    fun `selects exactly one authorized physical phone`() {
        val root = createTempDirectory("adb-selection").toFile()
        try {
            val adb = executable(root, "adb", """
                if [ "${'$'}1" = devices ]; then
                  printf 'List of devices attached\nphone-1 device product:test\nemulator-1 device product:test\n'
                else
                  printf 'Pixel Test\n'
                fi
            """)
            assertEquals("phone-1" to "Pixel Test", Adb(adb).physicalPhone(null))
            assertFailsWith<IllegalStateException> { Adb(adb).physicalPhone("missing") }
            assertFailsWith<IllegalStateException> { Adb(adb).physicalPhone("emulator-1") }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `install uses canonical package and launcher command`() {
        val root = createTempDirectory("adb-install").toFile()
        try {
            val log = root.resolve("commands")
            val adb = executable(root, "adb", """
                printf '%s\n' "${'$'}*" >> '${log.absolutePath}'
                case "${'$'}*" in
                  'devices -l') printf 'List of devices attached\nphone-1 device product:test\n' ;;
                  *'getprop ro.product.model') printf 'Pixel Test\n' ;;
                  *'pm path ${CodexMobileAutomation.App.APPLICATION_ID}') printf 'package:/data/app/base.apk\n' ;;
                  *) printf 'Success\n' ;;
                esac
            """)
            val apk = root.resolve("release.apk").apply { writeText("apk") }
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            val task = project.tasks.create("installPhoneTest", InstallPhoneTask::class.java).apply {
                adbExecutable.set(adb)
                releaseApk.set(apk)
            }
            task.install()
            val commands = log.readText()
            assertTrue("install -r ${apk.absolutePath}" in commands)
            assertTrue("pm path ${CodexMobileAutomation.App.APPLICATION_ID}" in commands)
            assertTrue("-c ${CodexMobileAutomation.App.LAUNCHER_CATEGORY} 1" in commands)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `instrumentation failure includes logcat diagnostics`() {
        val root = createTempDirectory("adb-smoke").toFile()
        try {
            val adb = executable(root, "adb", """
                case "${'$'}*" in
                  *'am instrument'*) printf 'INSTRUMENTATION_FAILED\n'; exit 1 ;;
                  'logcat -d -b all -v threadtime -t 1000') printf 'diagnostic-marker\n' ;;
                  *) printf 'Success\n' ;;
                esac
            """)
            val artifacts = root.resolve("artifacts").also { it.mkdirs() }
            artifacts.resolve(CodexMobileAutomation.Artifacts.DEBUG_APK).writeText("app")
            artifacts.resolve(CodexMobileAutomation.Artifacts.DEBUG_TEST_APK).writeText("test")
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            val task = project.tasks.create("smokeTest", AndroidDeviceSmokeTask::class.java).apply {
                adbExecutable.set(adb)
                artifactsDirectory.set(artifacts)
                mode.set("platform")
            }
            val failure = assertFailsWith<IllegalStateException> { task.smoke() }
            assertTrue("diagnostic-marker" in failure.message!!)
            assertTrue(AndroidSmokeCase.PACKAGING_CHECKSUM.selector in failure.message!!)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun executable(root: java.io.File, name: String, body: String) = root.resolve(name).apply {
        writeText("#!/bin/sh\nset -eu\n${body.trimIndent()}\n")
        assertTrue(setExecutable(true))
    }
}
