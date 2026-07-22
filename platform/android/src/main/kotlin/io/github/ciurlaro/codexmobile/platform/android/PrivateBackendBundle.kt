package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

internal enum class PrivateBackend(val library: String) {
    MUTOOL("libcodex_mutool.so"),
    TESSERACT("libcodex_tesseract.so"),
    OFFICE("libcodex_officecli.so"),
    TELEGRAM("libcodex_tgcli.so"),
}

internal class PrivateBackendBundle(private val context: Context) {
    private val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    private val backendDirectory = File(context.noBackupFilesDir, "private-backends")

    @Synchronized
    fun prepare() {
        val version = File(backendDirectory, VERSION_MARKER)
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
        if (version != BuildConfig.PRIVATE_BACKEND_BUNDLE_VERSION) installAssets()
        PrivateBackend.entries.forEach { backend ->
            check(File(nativeDirectory, backend.library).let { it.isFile && it.canExecute() }) {
                "A private native backend is missing"
            }
        }
    }

    fun startPrivateBackend(
        backend: PrivateBackend,
        arguments: List<String>,
        extraEnvironment: Map<String, String> = emptyMap(),
        workingDirectory: File = context.filesDir,
        mergeError: Boolean = true,
    ): Process {
        prepare()
        val executable = File(nativeDirectory, backend.library)
        return ProcessBuilder(listOf(executable.absolutePath) + arguments)
            .directory(workingDirectory)
            .redirectErrorStream(mergeError)
            .apply {
                environment().clear()
                environment()["PATH"] = "/system/bin:/system/xbin"
                environment()["LD_LIBRARY_PATH"] = nativeDirectory.absolutePath
                environment()["HOME"] = File(context.filesDir, "home").requireDirectory().absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                when (backend) {
                    PrivateBackend.MUTOOL -> Unit
                    PrivateBackend.TESSERACT ->
                        environment()["TESSDATA_PREFIX"] = File(backendDirectory, "tessdata").absolutePath
                    PrivateBackend.OFFICE -> {
                        environment()["CODEX_MOBILE_OFFICECLI_ENTRY"] =
                            File(backendDirectory, "officecli/officecli").absolutePath
                        environment()["OFFICECLI_SKIP_UPDATE"] = "1"
                    }
                    PrivateBackend.TELEGRAM -> {
                        environment()["CODEX_MOBILE_TGCLI_ENTRY"] =
                            File(backendDirectory, "tgcli/cli.js").absolutePath
                        environment()["TGCLI_STORE"] = telegramStore.absolutePath
                        environment()["NODE_OPTIONS"] = "--no-warnings"
                    }
                }
                environment().putAll(extraEnvironment)
            }
            .start()
    }

    val telegramStore: File
        get() = File(context.noBackupFilesDir, "telegram")

    val snapshotsDirectory: File
        get() = File(context.noBackupFilesDir, "document-snapshots").requireDirectory()

    private fun installAssets() {
        val candidate = File(context.noBackupFilesDir, "private-backends.stage")
        candidate.deleteRecursively()
        check(candidate.mkdirs()) { "Unable to prepare private backend assets" }
        try {
            context.assets.open("private-backends/tgcli.zip").use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val output = File(candidate, "tgcli/${entry.name}")
                        check(output.canonicalPath.startsWith(candidate.canonicalPath + File.separator)) {
                            "Invalid bundled tgcli path"
                        }
                        if (entry.isDirectory) {
                            check(output.isDirectory || output.mkdirs()) {
                                "Unable to create tgcli directory"
                            }
                        } else {
                            output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                            FileOutputStream(output).buffered().use { target -> zip.copyTo(target) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            copyAsset("private-backends/officecli/officecli", File(candidate, "officecli/officecli"))
            copyAsset("private-backends/tessdata/eng.traineddata", File(candidate, "tessdata/eng.traineddata"))
            File(candidate, VERSION_MARKER).writeText(BuildConfig.PRIVATE_BACKEND_BUNDLE_VERSION)

            backendDirectory.deleteRecursively()
            check(candidate.renameTo(backendDirectory)) { "Unable to activate private backend assets" }
        } catch (error: Exception) {
            candidate.deleteRecursively()
            throw error
        }
    }

    private fun copyAsset(asset: String, output: File) {
        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        context.assets.open(asset).use { input ->
            FileOutputStream(output).buffered().use { target -> input.copyTo(target) }
        }
    }

    private fun File.requireDirectory(): File = apply {
        check(isDirectory || mkdirs()) { "Unable to prepare private backend directory" }
    }

    private companion object {
        const val VERSION_MARKER = ".bundle-version"
    }
}
