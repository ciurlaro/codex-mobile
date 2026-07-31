import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Executes the packaged native process")
abstract class NativeRuntimeSmokeTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifactsDirectory: DirectoryProperty
    @get:Input abstract val expectedBinarySha256: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val certificateFile: RegularFileProperty
    @get:Input abstract val ioTimeoutMillis: Property<Long>
    @get:Input abstract val processTimeoutMillis: Property<Long>

    init {
        ioTimeoutMillis.convention(30_000L)
        processTimeoutMillis.convention(30_000L)
    }

    @TaskAction
    fun smoke() {
        val expected = expectedBinarySha256.get()
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "invalid expected native runtime SHA-256" }
        val work = temporaryDir.resolve("native-smoke").also { it.deleteRecursively(); it.mkdirs() }
        try {
            val runtime = extractRuntime(work)
            check(runtime.sha256() == expected) { "packaged native runtime SHA-256 mismatch" }
            check(runtime.setExecutable(true, false) && runtime.canExecute()) { "could not make native runtime executable" }
            val home = work.resolve("home").also { it.mkdirs() }
            val temporary = work.resolve("tmp").also { it.mkdirs() }
            val codexHome = work.resolve("codex").also { it.mkdirs() }
            val certificate = certificateFile.get().asFile
            check(certificate.isFile && certificate.length() > 0) { "missing system CA certificate bundle: $certificate" }
            repeat(2) { cycle ->
                runCycle(runtime, home, temporary, codexHome, certificate)
                verifySqlite(codexHome.resolve("logs_2.sqlite"))
                logger.lifecycle("Native App Server cycle ${cycle + 1} passed.")
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun extractRuntime(work: File): File {
        val apk = artifactsDirectory.get().asFile.resolve(CodexMobileAutomation.Artifacts.DEBUG_APK)
        check(apk.isFile) { "missing staged debug APK: $apk" }
        val entryName = "lib/${CodexMobileAutomation.App.ABI}/${CodexMobileAutomation.App.RUNTIME_LIBRARY}"
        val runtime = work.resolve(CodexMobileAutomation.App.RUNTIME_LIBRARY)
        ZipFile(apk).use { zip ->
            val matches = zip.entries().asSequence().filter { it.name == entryName }.toList()
            check(matches.size == 1 && !matches.single().isDirectory) { "APK must contain exactly one $entryName" }
            zip.getInputStream(matches.single()).use { input -> runtime.outputStream().use(input::copyTo) }
        }
        return runtime
    }

    @Suppress("UNCHECKED_CAST")
    private fun runCycle(runtime: File, home: File, temporary: File, codexHome: File, certificate: File) {
        val process = ProcessBuilder(runtime.absolutePath)
            .directory(home)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        process.environment().apply {
            clear()
            putAll(
                mapOf(
                    "PATH" to "/usr/bin:/bin", "LANG" to "C.UTF-8", "HOME" to home.absolutePath,
                    "TMPDIR" to temporary.absolutePath, "CODEX_HOME" to codexHome.absolutePath,
                    "CODEX_SQLITE_HOME" to codexHome.absolutePath, "SSL_CERT_FILE" to certificate.absolutePath,
                    "NO_COLOR" to "1",
                ),
            )
        }
        val running = process.start()
        val stderr = BoundedOutput(MAX_DIAGNOSTIC_BYTES)
        val stderrDrain = Thread { running.errorStream.use { stderr.copyFrom(it) } }.apply {
            name = "codex-native-smoke-stderr"
            isDaemon = true
            start()
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = running.outputStream.bufferedWriter()
            val reader = running.inputStream.bufferedReader()
            writer.appendLine(INITIALIZE)
            writer.flush()
            val response = executor.submit<String?> { reader.readLine() }
                .get(ioTimeoutMillis.get(), TimeUnit.MILLISECONDS)
                ?: error("native runtime closed stdout before initialize response")
            check(response.toByteArray().size <= MAX_JSON_LINE_BYTES) { "native runtime response exceeded JSONL limit" }
            val json = JsonSlurper().parseText(response) as? Map<String, Any?>
                ?: error("native runtime initialize response was not an object")
            val result = json["result"] as? Map<String, Any?> ?: error("native runtime initialize result is missing")
            check((json["id"] as? Number)?.toInt() == 1) { "native runtime initialize id mismatch" }
            check(result["codexHome"] == codexHome.absolutePath) { "native runtime reported the wrong Codex home" }
            check(result["platformFamily"] == "unix" && result["platformOs"] == "linux") {
                "native runtime reported an unexpected platform"
            }
            writer.appendLine(INITIALIZED)
            writer.flush()
            writer.close()
            check(running.waitFor(processTimeoutMillis.get(), TimeUnit.MILLISECONDS)) {
                "native runtime did not exit after stdin closed"
            }
            check(running.exitValue() == 0) { "native runtime exited ${running.exitValue()}: ${stderr.text()}" }
        } finally {
            executor.shutdownNow()
            if (running.isAlive) {
                running.descendants().forEach { it.destroyForcibly() }
                running.destroy()
                if (!running.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) running.destroyForcibly()
                running.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            }
            stderrDrain.join(STOP_TIMEOUT.toMillis())
        }
    }

    private fun verifySqlite(database: File) {
        check(database.isFile && database.length() >= SQLITE_HEADER.size) { "native runtime did not create SQLite evidence" }
        val header = database.inputStream().use { it.readNBytes(SQLITE_HEADER.size) }
        check(header.contentEquals(SQLITE_HEADER)) { "native runtime SQLite evidence is malformed" }
    }

    private class BoundedOutput(private val limit: Int) {
        private val bytes = ByteArrayOutputStream()
        @Synchronized fun copyFrom(input: InputStream) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return
                val remaining = limit - bytes.size()
                if (remaining > 0) bytes.write(buffer, 0, minOf(count, remaining))
            }
        }
        @Synchronized fun text(): String = bytes.toString(Charsets.UTF_8)
    }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val MAX_JSON_LINE_BYTES = 1024 * 1024
        private const val MAX_DIAGNOSTIC_BYTES = 1024 * 1024
        private val STOP_TIMEOUT = Duration.ofSeconds(5)
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray()
        private const val INITIALIZE =
            "{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"native_runtime_smoke\",\"title\":\"Native Runtime Smoke\",\"version\":\"1\"}}}"
        private const val INITIALIZED = "{\"method\":\"initialized\",\"params\":{}}"
    }
}
