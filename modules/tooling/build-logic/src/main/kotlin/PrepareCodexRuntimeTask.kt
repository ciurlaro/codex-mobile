import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class PrepareCodexRuntimeTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val codexVersion: Property<String>

    @get:Input
    abstract val archiveSha256: Property<String>

    @get:Input
    abstract val binarySha256: Property<String>

    @get:Input
    @get:Optional
    abstract val target: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val preparationScript: RegularFileProperty

    @get:OutputFile
    abstract val outputRuntime: RegularFileProperty

    @TaskAction
    fun prepare() {
        execOperations.exec {
            executable(preparationScript.get().asFile)
            args(
                codexVersion.get(),
                archiveSha256.get(),
                binarySha256.get(),
                outputRuntime.get().asFile.absolutePath,
            )
            target.orNull?.takeIf(String::isNotBlank)?.let { args(it) }
        }
    }
}
