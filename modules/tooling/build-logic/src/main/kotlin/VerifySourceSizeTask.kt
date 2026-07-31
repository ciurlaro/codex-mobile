import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Verification has no reusable outputs")
abstract class VerifySourceSizeTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun verifySourceSizes() {
        val root = repositoryRoot.get().asFile
        val generated = root.resolve(GENERATED_PATH)
        sources.files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { source ->
            val path = source.relativeTo(root).invariantSeparatorsPath
            val lines = source.useLines { it.count() }
            if (source.startsWith(generated)) {
                requireSize(lines in 100..300, "generated protocol shard must have 100-300 lines: $path ($lines)")
            } else {
                requireSize(lines <= 300, "handwritten Kotlin exceeds 300 lines: $path ($lines)")
            }
        }
        logger.lifecycle("source sizes verified")
    }

    private fun requireSize(condition: Boolean, message: String) {
        if (!condition) throw GradleException(message)
    }

    companion object {
        private const val GENERATED_PATH =
            "modules/multiplatform/codex-shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/generated"
    }
}
