import com.android.apksig.ApkVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Read-only release verification has no reusable output")
abstract class VerifyReleaseTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundle: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mapping: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val aapt2Executable: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sbom: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockfiles: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verificationMetadata: RegularFileProperty
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val runtimeSha256: Property<String>

    @TaskAction
    fun verify() {
        val apk = singleApk(apkDirectory.get().asFile)
        val result = ApkVerifier.Builder(apk).build().verify()
        check(result.isVerified && result.isVerifiedUsingV2Scheme) {
            "release APK signature verification failed: ${result.errors.joinToString()}"
        }
        verifyManifest(apk)
        verifyReleaseArchive(apk, CodexMobileAutomation.App.apkReleaseNativePaths, "", runtimeSha256.get())
        verifyReleaseArchive(bundle.get().asFile, CodexMobileAutomation.App.bundleReleaseNativePaths, "base/", null)
        check(mapping.get().asFile.length() > 0) { "release mapping is empty" }
        check(sbom.get().asFile.length() > 0) { "SBOM is empty" }
        lockfiles.files.forEach { check(it.isFile && it.length() > 0) { "missing dependency lock: $it" } }
        check(verificationMetadata.get().asFile.length() > 0) { "dependency verification metadata is empty" }
        logger.lifecycle("release verified")
    }

    private fun verifyManifest(apk: File) {
        val output = process(
            aapt2Executable.get().asFile.absolutePath, "dump", "xmltree", apk.absolutePath,
            "--file", "AndroidManifest.xml",
        )
        fun requireText(value: String) = check(value in output) { "release manifest is missing: $value" }
        fun requirePattern(pattern: Regex, description: String) =
            check(pattern.containsMatchIn(output)) { "release manifest has an invalid $description" }
        requirePattern(Regex("versionCode.*=${versionCode.get()}(?:\\s|$)"), "versionCode")
        requirePattern(Regex("versionName.*=\"${Regex.escape(versionName.get())}\""), "versionName")
        requirePattern(Regex("allowBackup.*=false"), "allowBackup policy")
        requirePattern(Regex("usesCleartextTraffic.*=false"), "cleartext policy")
        requireText("android.permission.INTERNET")
        requireText("MainActivity")
        requireText("CodexForegroundService")
        check("REQUEST_INSTALL_PACKAGES" !in output) { "release manifest requests package installation" }
        check(!Regex("debuggable.*=true").containsMatchIn(output)) { "release is debuggable" }
        check(Regex("exported.*=true").findAll(output).count() == 1) { "release must have exactly one exported component" }
    }

    private fun process(vararg command: String): String {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val drain = Thread { process.inputStream.use { it.copyTo(output) } }.apply { start() }
        check(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            "aapt2 timed out"
        }
        drain.join()
        val text = output.toString(Charsets.UTF_8)
        check(process.exitValue() == 0) { "aapt2 failed: $text" }
        return text
    }

    companion object {
        private val PROCESS_TIMEOUT = Duration.ofSeconds(30)
    }
}

@CacheableTask
abstract class CaptureReleaseBaselineTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty
    @get:OutputFile abstract val baseline: RegularFileProperty

    @TaskAction
    fun capture() {
        val source = singleApk(apkDirectory.get().asFile)
        val target = baseline.get().asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        logger.lifecycle("captured release baseline ${target.sha256()}")
    }
}

@DisableCachingByDefault(because = "Read-only byte comparison has no reusable output")
abstract class VerifyReleaseReproducibilityTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: RegularFileProperty

    @TaskAction
    fun verify() {
        check(baseline.isPresent && baseline.get().asFile.isFile) {
            "release reproducibility baseline is missing; first run clean :android:app:${CodexMobileAutomation.Tasks.CAPTURE_BASELINE} --no-build-cache"
        }
        val first = baseline.get().asFile
        val second = singleApk(apkDirectory.get().asFile)
        check(first.readBytes().contentEquals(second.readBytes())) {
            "release APK is not byte-for-byte reproducible (${first.sha256()} != ${second.sha256()})"
        }
        logger.lifecycle("release APK is byte-for-byte reproducible (${second.sha256()})")
    }
}

private fun singleApk(directory: File): File {
    val apks = directory.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
    check(apks.size == 1) { "expected exactly one release APK, found ${apks.size}" }
    return apks.single()
}

internal fun verifyReleaseArchive(
    archive: File,
    expectedNative: Set<String>,
    prefix: String,
    runtimeSha256: String?,
) {
    check(archive.isFile) { "release archive is missing: $archive" }
    ZipFile(archive).use { zip ->
        val entries = zip.entries().asSequence().toList()
        val names = entries.map { it.name }
        check(names.size == names.distinct().size) { "release archive has duplicate ZIP entries" }
        check(names.none { it.startsWith("/") || it.split('/').any { part -> part == ".." } }) {
            "release archive has an unsafe ZIP entry"
        }
        val native = names.filter { it.startsWith("${prefix}lib/") && it.endsWith(".so") }.toSortedSet()
        check(names.none { it.startsWith("${prefix}lib/x86/") || it.startsWith("${prefix}lib/x86_64/") ||
            it.startsWith("${prefix}lib/armeabi-v7a/") }) { "release contains a forbidden ABI" }
        check(native == expectedNative) { "unexpected release native libraries: $native" }
        if (prefix.isEmpty()) {
            val runtime = zip.getEntry(
                "lib/${CodexMobileAutomation.App.ABI}/${CodexMobileAutomation.App.RUNTIME_LIBRARY}",
            ) ?: error("release APK runtime is missing")
            val actual = zip.getInputStream(runtime).use(::sha256)
            check(actual == runtimeSha256) { "release APK runtime hash mismatch" }
        } else {
            check(names.count { it == "base/manifest/AndroidManifest.xml" } == 1) {
                "release bundle manifest is missing or ambiguous"
            }
        }
    }
}

private fun sha256(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun File.sha256(): String = inputStream().use(::sha256)
