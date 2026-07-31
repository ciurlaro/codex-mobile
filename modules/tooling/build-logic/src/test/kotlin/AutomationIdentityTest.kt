import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class AutomationIdentityTest {
    @Test
    fun `smoke modes parse strictly and own exact cases`() {
        assertEquals(AndroidSmokeMode.FULL, AndroidSmokeMode.parse("full"))
        assertEquals(AndroidSmokeMode.PLATFORM, AndroidSmokeMode.parse("PLATFORM"))
        assertTrue(assertFailsWith<IllegalArgumentException> { AndroidSmokeMode.parse("quick") }.message!!.contains("full, platform"))
        assertEquals(
            setOf(
                AndroidSmokeCase.BOOTSTRAP_FAILURES,
                AndroidSmokeCase.BOOTSTRAP_POLICIES,
                AndroidSmokeCase.PACKAGING_CHECKSUM,
                AndroidSmokeCase.PROCESS_LIFECYCLE,
            ),
            AndroidSmokeCase.forMode(AndroidSmokeMode.FULL).toSet(),
        )
        assertEquals(
            setOf(
                AndroidSmokeCase.BOOTSTRAP_FAILURES,
                AndroidSmokeCase.PACKAGING_CHECKSUM,
                AndroidSmokeCase.PLATFORM_PRIVACY,
            ),
            AndroidSmokeCase.forMode(AndroidSmokeMode.PLATFORM).toSet(),
        )
    }

    @Test
    fun `application and artifact identities derive from shared values`() {
        assertEquals("${CodexMobileAutomation.App.APPLICATION_ID}.debug", CodexMobileAutomation.App.DEBUG_APPLICATION_ID)
        assertEquals("${CodexMobileAutomation.App.DEBUG_APPLICATION_ID}.test", CodexMobileAutomation.App.TEST_APPLICATION_ID)
        assertTrue(
            "lib/${CodexMobileAutomation.App.ABI}/${CodexMobileAutomation.App.RUNTIME_LIBRARY}" in
                CodexMobileAutomation.App.apkReleaseNativePaths,
        )
        assertTrue(CodexMobileAutomation.App.bundleReleaseNativePaths.all { it.startsWith("base/lib/${CodexMobileAutomation.App.ABI}/") })
        assertEquals("codex-mobile-debug.apk", CodexMobileAutomation.Artifacts.DEBUG_APK)
        assertEquals("codex-mobile-debug-androidTest.apk", CodexMobileAutomation.Artifacts.DEBUG_TEST_APK)
    }

    @Test
    fun `configured smoke artifacts are repository relative`() {
        val directory = createTempDirectory("smoke-artifacts").toFile()
        try {
            val root = ProjectBuilder.builder().withProjectDir(directory).build()
            val appDirectory = directory.resolve("modules/android/app").also(File::mkdirs)
            val app = ProjectBuilder.builder().withParent(root).withProjectDir(appDirectory).build()
            val artifacts = CodexMobileAutomation.Artifacts.configuredSmoke(
                root.layout,
                app.providers.provider { CodexMobileAutomation.Artifacts.SMOKE_DIRECTORY },
            )
            assertEquals(directory.resolve("smoke-artifacts").canonicalFile, artifacts.get().asFile.canonicalFile)
        } finally {
            directory.deleteRecursively()
        }
    }
}
