import java.io.File
import java.security.MessageDigest
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
abstract class VerifyRepositoryStructureTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryFiles: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun verifyStructure() {
        val root = repositoryRoot.get().asFile
        val files = repositoryFiles.files.associateBy { it.relativePathFrom(root) }

        REQUIRED_FILES.forEach { path ->
            requireRule(root.resolve(path).isFile, "missing required file: $path")
        }
        requireRule(root.resolve("gradlew").canExecute(), "Gradle wrapper is not executable")

        val moduleRoots = files.keys.filter { it.endsWith("/build.gradle.kts") }
            .filter { it.startsWith("modules/") }
            .sorted()
        requireRule(
            moduleRoots == EXPECTED_MODULE_ROOTS,
            "unexpected Gradle module root; found ${moduleRoots.joinToString()}",
        )
        LEGACY_ROOTS.forEach { path ->
            requireRule(!root.resolve(path).exists(), "legacy root returned: $path")
        }
        val automationScript = files.keys.firstOrNull { it.endsWith(".sh") || it.endsWith(".py") }
        requireRule(automationScript == null, "tracked shell/Python automation returned: $automationScript")
        requireRule(
            !root.resolve("modules/multiplatform/codex-shared/src/androidMain").exists(),
            "shared production must be physically commonMain-only",
        )
        val moduleBuild = root.resolve("modules").walkTopDown()
            .onEnter { it.name !in setOf(".gradle", ".kotlin") }
            .firstOrNull { it.isDirectory && it.name == "build" }
        requireRule(moduleBuild == null, "module-local build directory found: ${moduleBuild?.relativePathFrom(root)}")
        requireRule(
            !root.resolve("modules/android/app/src/main/jniLibs").exists(),
            "native runtime must come from the versioned runtime artifact",
        )
        requireRule(
            !root.resolve("modules/multiplatform/codex-shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/agent").exists(),
            "portable agent source must come from codex-agent-client",
        )
        requireRule(
            !root.resolve("modules/multiplatform/codex-shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver").exists(),
            "App Server source must come from codex-agent-client",
        )
        requireRule(
            !root.resolve("modules/android/app/src/main/kotlin/io/github/ciurlaro/codexmobile/app/runtime/bootstrap").exists(),
            "Android runtime source must come from codex-agent-runtime-android",
        )

        files.keys.filter { it.endsWith(".kt") && it.startsWith("modules/") }.forEach { path ->
            requireRule(
                ALLOWED_KOTLIN_ROOTS.any(path::startsWith),
                "Kotlin source outside agreed scaffolding: $path",
            )
        }

        rejectLines(
            files,
            { it.startsWith(COMMON_MAIN) && it.endsWith(".kt") },
            Regex("""^\s*import\s+(android\.|java\.)"""),
            "platform import in commonMain",
        )

        val processOwners = matchingFiles(
            files,
            { it.startsWith("modules/android/app/src/main/") && it.endsWith(".kt") },
            Regex("""ProcessBuilder\(|java\.lang\.Process"""),
        )
        requireRule(
            processOwners.isEmpty(),
            "child-process ownership must remain in codex-agent-runtime-android; found ${processOwners.joinToString()}",
        )

        rejectLines(
            files,
            { isStaleReferenceScope(it) && it != VERIFIER_PATH && !isProtocolSource(it) },
            Regex(STALE_REFERENCES.joinToString("|", transform = Regex::escape)),
            "legacy reference",
        )
        rejectLines(
            files,
            {
                (it == "settings.gradle.kts" || it == "gradle/libs.versions.toml" ||
                    it.startsWith("modules/")) && (it.endsWith(".kts") || it.endsWith(".toml"))
            },
            Regex("""maven\.pkg\.github\.com|oss\.sonatype\.org/content/repositories/snapshots|mavenLocal\(\)|SNAPSHOT"""),
            "unapproved dependency repository",
        )
        rejectLines(
            files,
            { it.startsWith("modules/android/app/src/main/") },
            Regex("""REQUEST_INSTALL_PACKAGES|UPDATE_PACKAGES_WITHOUT_USER_ACTION|PackageInstaller"""),
            "forbidden package-install capability",
        )
        rejectLines(
            files,
            { it.startsWith("modules/") && it.endsWith(".kt") && it != VERIFIER_PATH },
            Regex("""@Ignore|TODO\s*\("""),
            "disabled or unfinished Kotlin test",
        )
        rejectLines(
            files,
            {
                it.endsWith(".kt") &&
                    (it.startsWith(COMMON_MAIN) || it.startsWith("modules/android/app/src/main/"))
            },
            Regex("""(^|[^A-Za-z0-9_])(Log\.[vdiwe]|println|print|System\.(out|err))\s*\("""),
            "production console logging",
        )

        val payload = files.keys.firstOrNull {
            it.startsWith("modules/android/app/src/main/") && it.substringAfterLast('.', "") in PAYLOAD_EXTENSIONS
        }
        requireRule(payload == null, "unexpected source-tree runtime payload: $payload")

        requireLine(root, "gradle.properties", "${CodexMobileAutomation.Properties.CODEX_VERSION}=0.145.0")
        requireLine(root, "gradle/libs.versions.toml", "codex-agent = \"0.1.0\"")
        requireLine(
            root,
            "gradle/libs.versions.toml",
            "codex-agent-client = { module = \"io.github.ciurlaro:codex-agent-client\", version.ref = \"codex-agent\" }",
        )
        requireLine(
            root,
            "gradle/libs.versions.toml",
            "codex-agent-runtime-android = { module = \"io.github.ciurlaro:codex-agent-runtime-android\", version.ref = \"codex-agent\" }",
        )
        requireRule(
            root.resolve("gradle/wrapper/gradle-wrapper.jar").sha256() == WRAPPER_SHA256,
            "Gradle wrapper JAR hash changed",
        )
        requireLineMatching(
            root,
            "gradle/wrapper/gradle-wrapper.properties",
            Regex("""distributionUrl=.*gradle-9\.4\.1-bin\.zip"""),
        )
        requireLine(
            root,
            "gradle/wrapper/gradle-wrapper.properties",
            "distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb",
        )
        requireText(root, "modules/android/app/src/main/AndroidManifest.xml", "android:allowBackup=\"false\"")
        requireText(root, "modules/android/app/src/main/AndroidManifest.xml", "android.permission.INTERNET")
        requireText(
            root,
            "modules/android/app/src/main/res/xml/network_security_config.xml",
            "<base-config cleartextTrafficPermitted=\"false\"",
        )
        listOf(".gradle/", ".kotlin/", "build/", "**/build/").forEach { pattern ->
            requireLine(root, ".gitignore", pattern)
        }
        logger.lifecycle("structure verified")
    }

    private fun rejectLines(
        files: Map<String, File>,
        include: (String) -> Boolean,
        pattern: Regex,
        description: String,
    ) {
        files.toSortedMap().forEach { (path, file) ->
            if (!include(path) || !file.isTextSource()) return@forEach
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    requireRule(!pattern.containsMatchIn(line), "$description: $path:${index + 1}")
                }
            }
        }
    }

    private fun matchingFiles(
        files: Map<String, File>,
        include: (String) -> Boolean,
        pattern: Regex,
    ): List<String> = files.toSortedMap().filter { (path, file) ->
        include(path) && file.isTextSource() && file.useLines { lines -> lines.any(pattern::containsMatchIn) }
    }.keys.toList()

    private fun requireLine(root: File, path: String, expected: String) {
        requireRule(root.resolve(path).useLines { expected in it.toSet() }, "$path is missing: $expected")
    }

    private fun requireLineMatching(root: File, path: String, expected: Regex) {
        requireRule(root.resolve(path).useLines { lines -> lines.any(expected::matches) }, "$path has an unexpected value")
    }

    private fun requireText(root: File, path: String, expected: String) {
        requireRule(expected in root.resolve(path).readText(), "$path is missing: $expected")
    }

    private fun requireRule(condition: Boolean, message: String) {
        if (!condition) throw GradleException("structure verification failed: $message")
    }

    private fun File.relativePathFrom(root: File): String = relativeTo(root).invariantSeparatorsPath

    private fun File.isTextSource(): Boolean =
        name in TEXT_FILENAMES || extension.lowercase() in TEXT_EXTENSIONS

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

    private fun isStaleReferenceScope(path: String): Boolean =
        path in ROOT_TEXT_FILES || path.startsWith("docs/") ||
            path.startsWith(".github/") || path.startsWith("modules/")

    private fun isProtocolSource(path: String): Boolean =
        "/protocol/schema/" in path || "/protocol/generated/" in path

    companion object {
        private const val COMMON_MAIN = "modules/multiplatform/codex-shared/src/commonMain/kotlin/"
        private const val VERIFIER_PATH =
            "modules/tooling/build-logic/src/main/kotlin/VerifyRepositoryStructureTask.kt"
        private const val WRAPPER_SHA256 = "55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c"
        private val REQUIRED_FILES = listOf(
            "README.md", "docs/project/LICENSES/LICENSE", "docs/project/THIRD_PARTY_NOTICES.md",
            "docs/project/CONTRIBUTING.md", "docs/project/SECURITY.md",
            "release-signing-certificate.sha256", "settings.gradle.kts", "gradle.properties",
            "gradle/verification-metadata.xml", "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties", "docs/technical/requirements.md",
            "docs/technical/architecture.md", "docs/technical/objects.md",
            "docs/technical/decisions.md", "docs/technical/security.md",
            "docs/technical/privacy.md", "docs/technical/release.md",
            "docs/technical/sbom.cdx.json", "settings-gradle.lockfile",
            "modules/android/app/gradle.lockfile", "modules/multiplatform/codex-shared/gradle.lockfile",
            "modules/tooling/build-logic/gradle.lockfile",
            "modules/tooling/build-logic/settings-gradle.lockfile", "modules/android/app/build.gradle.kts",
            "modules/android/app/src/main/AndroidManifest.xml",
            "modules/android/app/src/main/kotlin/io/github/ciurlaro/codexmobile/app/composition/AndroidPlatform.kt",
            "modules/multiplatform/codex-shared/build.gradle.kts",
            "modules/tooling/build-logic/build.gradle.kts", "modules/tooling/build-logic/settings.gradle.kts",
            VERIFIER_PATH, "modules/tooling/build-logic/src/main/kotlin/VerifySourceSizeTask.kt",
            "modules/tooling/build-logic/src/main/kotlin/codexmobile.repository-verification.gradle.kts",
            "modules/tooling/build-logic/src/main/kotlin/CodexMobileAutomation.kt",
        )
        private val EXPECTED_MODULE_ROOTS = listOf(
            "modules/android/app/build.gradle.kts", "modules/multiplatform/codex-shared/build.gradle.kts",
            "modules/tooling/build-logic/build.gradle.kts",
        )
        private val LEGACY_ROOTS = listOf(
            "src", "core", "agent", "app", "app-server-client", "platform", "provider-api",
            "runtime-host", "build-logic", "tools", "providers", "scripts",
        )
        private val ALLOWED_KOTLIN_ROOTS = listOf(
            COMMON_MAIN, "modules/multiplatform/codex-shared/src/commonTest/kotlin/",
            "modules/android/app/src/main/kotlin/", "modules/android/app/src/test/kotlin/",
            "modules/android/app/src/androidTest/kotlin/", "modules/tooling/build-logic/src/main/kotlin/",
            "modules/tooling/build-logic/src/test/kotlin/",
        )
        private val STALE_REFERENCES = listOf(
            "codex-mobile-plugins", "provider-api", "extension-provider-api", "agent/codex",
            "app-server-client", "runtime-host", "platform/android", "app/android", "src/modules",
            "kmp-process", "kmp-file", "kotlinx-io", "ktor-network",
            "codexMobile.provider",
        )
        private val ROOT_TEXT_FILES = setOf(
            "README.md", ".gitignore",
            "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
        )
        private val TEXT_FILENAMES = setOf("README", "LICENSE", ".gitignore")
        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "md", "toml", "properties", "lockfile", "yml", "yaml", "sh", "py", "xml", "json", "txt",
        )
        private val PAYLOAD_EXTENSIONS = setOf("js", "mjs", "cjs", "zip", "so")
    }
}
