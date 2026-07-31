import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

internal class Adb(private val executable: java.io.File, private val serial: String? = null) {
    fun run(vararg arguments: String, timeout: Duration = Duration.ofMinutes(2)): String {
        val command = buildList {
            add(executable.absolutePath)
            if (serial != null) addAll(listOf("-s", serial))
            addAll(arguments)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val drain = Thread { process.inputStream.use { it.copyTo(output) } }.apply { start() }
        check(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            "adb command timed out: ${arguments.joinToString(" ")}"
        }
        drain.join()
        val text = output.toString(Charsets.UTF_8)
        check(process.exitValue() == 0) { "adb command failed: ${arguments.joinToString(" ")}\n$text" }
        return text.replace("\r", "")
    }

    fun physicalPhone(requested: String?): Pair<String, String> {
        val devices = run("devices", "-l").lineSequence().drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 2 && it[1] == "device" && !it[0].startsWith("emulator-") }
            .map { it[0] }.toList()
        val selected = when {
            requested != null && requested.startsWith("emulator-") -> error("ANDROID_SERIAL names an emulator, not a phone")
            requested != null && requested !in devices -> error("ANDROID_SERIAL=$requested is not an authorized physical device")
            requested != null -> requested
            devices.isEmpty() -> error("no authorized physical phone found; connect and unlock it, then accept USB debugging")
            devices.size > 1 -> error("more than one physical phone is connected; set ANDROID_SERIAL")
            else -> devices.single()
        }
        val model = Adb(executable, selected).run("shell", "getprop", "ro.product.model").trim()
        return selected to model.ifBlank { "unknown" }
    }
}

@CacheableTask
abstract class StageSmokeArtifactsTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appApkDirectory: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testApkDirectory: DirectoryProperty
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val app = singleApk(appApkDirectory, "debug app")
        val test = singleApk(testApkDirectory, "debug instrumentation")
        val output = outputDirectory.get().asFile.also { it.mkdirs() }
        app.copyTo(output.resolve(CodexMobileAutomation.Artifacts.DEBUG_APK), overwrite = true)
        test.copyTo(output.resolve(CodexMobileAutomation.Artifacts.DEBUG_TEST_APK), overwrite = true)
    }

    private fun singleApk(directory: DirectoryProperty, label: String): java.io.File {
        val files = directory.get().asFile.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        check(files.size == 1) { "expected exactly one $label APK, found ${files.size}" }
        return files.single()
    }
}

@DisableCachingByDefault(because = "Installs and launches an application on a physical device")
abstract class InstallPhoneTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val adbExecutable: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseApk: RegularFileProperty
    @get:Input @get:Optional
    abstract val requestedSerial: Property<String>

    @TaskAction
    fun install() {
        val adb = Adb(adbExecutable.get().asFile)
        val (serial, model) = adb.physicalPhone(requestedSerial.orNull)
        val device = Adb(adbExecutable.get().asFile, serial)
        logger.lifecycle("target phone: $model ($serial)")
        device.run("install", "-r", releaseApk.get().asFile.absolutePath)
        val installed = device.run("shell", "pm", "path", CodexMobileAutomation.App.APPLICATION_ID)
        check(installed.lineSequence().any { it.endsWith("/base.apk") }) {
            "Android did not report the installed canonical package"
        }
        device.run(
            "shell", "monkey", "-p", CodexMobileAutomation.App.APPLICATION_ID,
            "-c", CodexMobileAutomation.App.LAUNCHER_CATEGORY, "1",
        )
        logger.lifecycle("Codex Mobile updated in place and opened on $model")
    }
}

@DisableCachingByDefault(because = "Runs instrumentation on a connected Android device")
abstract class AndroidDeviceSmokeTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val adbExecutable: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifactsDirectory: DirectoryProperty
    @get:Input abstract val mode: Property<String>

    @TaskAction
    fun smoke() {
        val selectedMode = AndroidSmokeMode.parse(mode.get())
        val artifacts = artifactsDirectory.get().asFile
        val adb = Adb(adbExecutable.get().asFile)
        adb.run("install", "-r", artifacts.resolve(CodexMobileAutomation.Artifacts.DEBUG_APK).absolutePath)
        adb.run("install", "-r", artifacts.resolve(CodexMobileAutomation.Artifacts.DEBUG_TEST_APK).absolutePath)
        adb.run("shell", "cmd", "package", "compile", "-f", "-m", "speed", CodexMobileAutomation.App.DEBUG_APPLICATION_ID)
        AndroidSmokeCase.forMode(selectedMode).forEach { case ->
            try {
                val output = adb.run(
                    "shell", "am", "instrument", "-w", "-r", "-e", "class", case.selector,
                    "${CodexMobileAutomation.App.TEST_APPLICATION_ID}/${CodexMobileAutomation.App.TEST_RUNNER}",
                    timeout = INSTRUMENTATION_TIMEOUT,
                )
                check(output.lineSequence().any { it.startsWith("OK (") }) {
                    "instrumentation did not report success for ${case.selector}\n$output"
                }
            } catch (failure: Throwable) {
                val logcat = runCatching { adb.run("logcat", "-d", "-b", "all", "-v", "threadtime", "-t", "1000") }
                    .getOrElse { "logcat unavailable: ${it.message}" }
                throw IllegalStateException("Android smoke failed for ${case.selector}\n$logcat", failure)
            }
        }
    }

    companion object {
        private val INSTRUMENTATION_TIMEOUT = Duration.ofMinutes(3)
    }
}
