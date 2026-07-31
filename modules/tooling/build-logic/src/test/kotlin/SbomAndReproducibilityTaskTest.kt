import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testfixtures.ProjectBuilder

class SbomAndReproducibilityTaskTest {
    @Test
    fun `SBOM output is deterministic and verification rejects drift`() {
        val project = fixture()
        try {
            project.resolve("app.lockfile").writeText(
                "example.group:example-library:1.2.3=releaseRuntimeClasspath\n" +
                    "ignored:debug-only:1.0=debugRuntimeClasspath\n",
            )
            project.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("codexmobile.repository-verification")
                }
                """.trimIndent(),
            )
            project.resolve("modules/android/app").mkdirs()
            project.resolve("modules/android/app/gradle.lockfile").writeText(project.resolve("app.lockfile").readText())
            project.resolve("gradle.properties").writeText(
                """
                codexMobile.versionName=1.0
                codexMobile.codexVersion=0.145.0
                codexMobile.codexArchiveSha256=${"a".repeat(64)}
                codexMobile.codexBinarySha256=${"b".repeat(64)}
                """.trimIndent(),
            )
            val first = run(project, "updateSbom")
            assertEquals(TaskOutcome.SUCCESS, first.task(":updateSbom")?.outcome)
            val output = project.resolve("docs/technical/sbom.cdx.json")
            val golden = output.readText()
            assertTrue(golden.endsWith("\n"))
            assertTrue("\"serialNumber\": \"urn:uuid:5949f150-8ceb-5825-85ab-f77f4d9411ed\"" in golden)
            assertTrue("pkg:maven/example.group/example-library@1.2.3" in golden)
            assertEquals(TaskOutcome.UP_TO_DATE, run(project, "updateSbom").task(":updateSbom")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, run(project, "verifySbom").task(":verifySbom")?.outcome)

            output.appendText("drift")
            assertTrue(runAndFail(project, "verifySbom").output.contains("is stale"))
            run(project, "updateSbom")
            assertEquals(golden, output.readText())
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `reproducibility reports matches mismatches and a missing baseline`() {
        val project = fixture()
        try {
            project.resolve("apks").mkdir()
            project.resolve("apks/release.apk").writeText("same")
            val gradleProject = ProjectBuilder.builder().withProjectDir(project).build()
            val capture = gradleProject.tasks.create("capture", CaptureReleaseBaselineTask::class.java).apply {
                apkDirectory.set(gradleProject.layout.projectDirectory.dir("apks"))
                baseline.set(gradleProject.layout.projectDirectory.file("evidence/release.apk"))
            }
            val verify = gradleProject.tasks.create("verifyRepro", VerifyReleaseReproducibilityTask::class.java).apply {
                apkDirectory.set(gradleProject.layout.projectDirectory.dir("apks"))
                baseline.set(gradleProject.layout.projectDirectory.file("evidence/release.apk"))
            }
            assertTrue(kotlin.runCatching(verify::verify).exceptionOrNull()!!.message!!.contains("baseline is missing"))
            capture.capture()
            verify.verify()
            project.resolve("apks/release.apk").writeText("changed")
            assertTrue(kotlin.runCatching(verify::verify).exceptionOrNull()!!.message!!.contains("not byte-for-byte reproducible"))
        } finally {
            project.deleteRecursively()
        }
    }

    private fun fixture() = createTempDirectory("automation-task").toFile().apply {
        resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
    }

    private fun run(project: File, task: String) = runner(project, task).build()
    private fun runAndFail(project: File, task: String) = runner(project, task).buildAndFail()
    private fun runner(project: File, task: String) = GradleRunner.create()
        .withProjectDir(project).withPluginClasspath().withArguments(task, "--configuration-cache", "--stacktrace")
}
