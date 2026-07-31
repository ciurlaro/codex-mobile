import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Verification has no reusable outputs")
abstract class VerifyReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val configured: Property<Boolean>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val storeFile: RegularFileProperty

    @TaskAction
    fun verifySigningConfiguration() {
        check(configured.get()) {
            "Release signing requires codexMobile.release.{storeFile,storePassword,keyAlias,keyPassword} " +
                "Gradle properties or the matching CODEX_MOBILE_RELEASE_* environment variables"
        }
        check(storeFile.isPresent) { "Release keystore does not exist" }
    }
}
