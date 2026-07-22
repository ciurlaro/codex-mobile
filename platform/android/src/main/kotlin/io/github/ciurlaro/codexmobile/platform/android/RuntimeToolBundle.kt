package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

internal class RuntimeToolBundle(private val context: Context) {
    private val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    private val runtimeDirectory = File(context.noBackupFilesDir, "runtime-tools")
    private val binDirectory = File(context.noBackupFilesDir, "tool-bin")

    @Synchronized
    fun prepareEnvironment(codexHome: File): Map<String, String> {
        val version = File(runtimeDirectory, VERSION_MARKER)
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
        if (version != BuildConfig.NATIVE_BUNDLE_VERSION) installRuntimeAssets()
        installAliases()
        installSkills(codexHome)

        return mapOf(
            "PATH" to listOf(
                binDirectory.absolutePath,
                System.getenv("PATH").orEmpty(),
                "/system/bin:/system/xbin",
            ).filter(String::isNotBlank).joinToString(":"),
            "LD_LIBRARY_PATH" to nativeDirectory.absolutePath,
            "CODEX_MOBILE_TGCLI_ENTRY" to File(runtimeDirectory, "tgcli/cli.js").absolutePath,
            "CODEX_MOBILE_OFFICECLI_ENTRY" to File(runtimeDirectory, "officecli/officecli").absolutePath,
            "TGCLI_STORE" to telegramStore.absolutePath,
            "TESSDATA_PREFIX" to File(runtimeDirectory, "tessdata").absolutePath,
            "OFFICECLI_SKIP_UPDATE" to "1",
            "NODE_OPTIONS" to "--no-warnings",
        )
    }

    fun startBundledCommand(
        command: String,
        arguments: List<String>,
        extraEnvironment: Map<String, String>,
    ): Process {
        val codexHome = File(context.noBackupFilesDir, "codex").requireDirectory()
        val environment = prepareEnvironment(codexHome)
        val executable = File(binDirectory, command)
        check(executable.exists()) { "Bundled command is unavailable: $command" }
        return ProcessBuilder(listOf(executable.absolutePath) + arguments)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(environment)
                environment().putAll(extraEnvironment)
                environment()["HOME"] = File(context.filesDir, "home").requireDirectory().absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .start()
    }

    val telegramStore: File
        get() = File(context.noBackupFilesDir, "telegram")

    private fun installRuntimeAssets() {
        val candidate = File(context.noBackupFilesDir, "runtime-tools-next")
        candidate.deleteRecursively()
        check(candidate.mkdirs()) { "Unable to prepare bundled tool assets" }
        try {
            context.assets.open("runtime/tgcli.zip").use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val output = File(candidate, "tgcli/${entry.name}")
                        check(output.canonicalPath.startsWith(candidate.canonicalPath + File.separator)) {
                            "Invalid bundled tgcli path"
                        }
                        if (entry.isDirectory) {
                            check(output.isDirectory || output.mkdirs()) { "Unable to create tgcli directory" }
                        } else {
                            output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                            FileOutputStream(output).buffered().use { destination ->
                                zip.copyTo(destination)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            copyAsset("runtime/officecli/officecli", File(candidate, "officecli/officecli"))
            copyAsset("runtime/tessdata/eng.traineddata", File(candidate, "tessdata/eng.traineddata"))
            File(candidate, VERSION_MARKER).writeText(BuildConfig.NATIVE_BUNDLE_VERSION)

            runtimeDirectory.deleteRecursively()
            check(candidate.renameTo(runtimeDirectory)) { "Unable to activate bundled tool assets" }
        } catch (error: Exception) {
            candidate.deleteRecursively()
            throw error
        }
    }

    private fun installAliases() {
        check(binDirectory.isDirectory || binDirectory.mkdirs()) { "Unable to prepare tool PATH" }
        EXECUTABLES.forEach { (name, library) ->
            val target = File(nativeDirectory, library)
            check(target.isFile && target.canExecute()) { "Bundled command is missing: $name" }
            val alias = File(binDirectory, name)
            if (runCatching { alias.canonicalFile == target.canonicalFile }.getOrDefault(false)) return@forEach
            Files.deleteIfExists(alias.toPath())
            Os.symlink(target.absolutePath, alias.absolutePath)
        }
    }

    private fun installSkills(codexHome: File) {
        BUNDLED_SKILLS.filterNot { isBundledSkillRemoved(codexHome, it) }.forEach { name ->
            installBundledSkill(codexHome, name)
        }
    }

    @Synchronized
    fun installBundledSkill(codexHome: File, name: String) {
        require(name in BUNDLED_SKILLS) { "Unknown bundled skill" }
        clearBundledSkillRemoval(codexHome, name)
        copyAsset("codex/skills/$name/SKILL.md", File(codexHome, "skills/$name/SKILL.md"))
    }

    fun readBundledSkill(name: String): String {
        require(name in BUNDLED_SKILLS) { "Unknown bundled skill" }
        return context.assets.open("codex/skills/$name/SKILL.md").bufferedReader().use { it.readText() }
    }

    @Synchronized
    fun markBundledSkillRemoved(codexHome: File, name: String) {
        require(name in BUNDLED_SKILLS) { "Unknown bundled skill" }
        val marker = removedSkillMarker(codexHome, name)
        check(marker.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val next = File(marker.parentFile, ".${marker.name}.next")
        next.writeText("removed\n")
        check(!marker.exists() || marker.delete()) { "Unable to replace removed bundled skill state" }
        check(next.renameTo(marker)) { "Unable to remember removed bundled skill" }
    }

    @Synchronized
    fun clearBundledSkillRemoval(codexHome: File, name: String) {
        require(name in BUNDLED_SKILLS) { "Unknown bundled skill" }
        check(!removedSkillMarker(codexHome, name).exists() || removedSkillMarker(codexHome, name).delete()) {
            "Unable to restore bundled skill state"
        }
    }

    fun isBundledSkillRemoved(codexHome: File, name: String): Boolean =
        removedSkillMarker(codexHome, name).isFile

    private fun removedSkillMarker(codexHome: File, name: String) =
        File(codexHome, ".codex-mobile/removed-skills/$name")

    private fun copyAsset(asset: String, output: File) {
        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val temporary = File(output.parentFile, ".${output.name}.next")
        context.assets.open(asset).use { input ->
            FileOutputStream(temporary).buffered().use { destination ->
                input.copyTo(destination)
            }
        }
        check(!output.exists() || output.delete()) { "Unable to update bundled asset" }
        check(temporary.renameTo(output)) { "Unable to activate bundled asset" }
    }

    private fun File.requireDirectory(): File = apply {
        check(isDirectory || mkdirs()) { "Unable to prepare private runtime directory" }
    }

    companion object {
        const val VERSION_MARKER = ".bundle-version"
        val EXECUTABLES = mapOf(
            "mutool" to "libcodex_mutool.so",
            "tesseract" to "libcodex_tesseract.so",
            "officecli" to "libcodex_officecli.so",
            "tgcli" to "libcodex_tgcli.so",
        )
        val BUNDLED_SKILLS = listOf("local-documents", "tgcli")
    }
}
