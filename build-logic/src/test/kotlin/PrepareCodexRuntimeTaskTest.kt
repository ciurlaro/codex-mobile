import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class PrepareCodexRuntimeTaskTest {
    @Test
    fun `task is up to date and reuses configuration cache`() {
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
                    printf '%s' "${'$'}1:${'$'}2:${'$'}3" > "${'$'}4"
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
            val second = run(project)
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(":prepareCodexRuntime")?.outcome)
            assertTrue(second.output.contains("Reusing configuration cache."))
        } finally {
            project.deleteRecursively()
        }
    }

    private fun run(project: File) = GradleRunner.create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments(
            "prepareCodexRuntime",
            "-PcodexMobile.codexVersion=1.2.3",
            "-PcodexMobile.codexArchiveSha256=archive",
            "-PcodexMobile.codexBinarySha256=binary",
            "--configuration-cache",
        )
        .build()
}
