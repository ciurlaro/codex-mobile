import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class PrepareCodexRuntimeTaskTest {
    @Test
    fun `task declares generated output and tracks input changes`() {
        val project = createTempDirectory("codex-runtime-task").toFile()
        try {
            project.resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            project.resolve("scripts").mkdir()
            project.resolve("scripts/prepare-codex-runtime.sh").apply {
                writeText(
                    """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    mkdir -p "${'$'}(dirname "${'$'}4")"
                    printf '%s' "${'$'}1:${'$'}2:${'$'}3:${'$'}{5:-arm64}" > "${'$'}4"
                    """.trimIndent(),
                )
                assertTrue(setExecutable(true))
            }
            project.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("codexmobile.codex-runtime")
                }
                """.trimIndent(),
            )

            val first = run(project)
            assertEquals(TaskOutcome.SUCCESS, first.task(":prepareCodexRuntime")?.outcome)
            val output = project.resolve(
                "build/generated/codex-runtime/main/arm64-v8a/libcodex_app_server.so",
            )
            assertEquals("1.2.3:archive:binary:arm64", output.readText())
            assertFalse(project.resolve("src/main/jniLibs").exists())

            val second = run(project)
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(":prepareCodexRuntime")?.outcome)
            assertTrue(second.output.contains("Reusing configuration cache."))

            val changed = run(project, version = "2.0.0")
            assertEquals(TaskOutcome.SUCCESS, changed.task(":prepareCodexRuntime")?.outcome)
            assertEquals("2.0.0:archive:binary:arm64", output.readText())
        } finally {
            project.deleteRecursively()
        }
    }

    private fun run(
        project: File,
        version: String = "1.2.3",
    ) = GradleRunner.create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments(
            "prepareCodexRuntime",
            "-PcodexMobile.codexVersion=$version",
            "-PcodexMobile.codexArchiveSha256=archive",
            "-PcodexMobile.codexBinarySha256=binary",
            "--configuration-cache",
        )
        .build()
}
