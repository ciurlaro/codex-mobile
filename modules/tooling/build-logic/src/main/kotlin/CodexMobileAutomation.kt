import org.gradle.api.file.Directory
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.Provider

internal object CodexMobileAutomation {
    object App {
        const val APPLICATION_ID = "io.github.ciurlaro.codexmobile"
        const val DEBUG_APPLICATION_ID = "$APPLICATION_ID.debug"
        const val TEST_APPLICATION_ID = "$DEBUG_APPLICATION_ID.test"
        const val ABI = "arm64-v8a"
        const val RUNTIME_LIBRARY = "libcodex_app_server.so"
        const val TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
        const val LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"

        val releaseNativeLibraries = setOf(
            "libandroidx.graphics.path.so",
            RUNTIME_LIBRARY,
            "libdatastore_shared_counter.so",
            "libratex_ffi.so",
        )
        val apkReleaseNativePaths = releaseNativeLibraries.mapTo(sortedSetOf()) { "lib/$ABI/$it" }
        val bundleReleaseNativePaths = releaseNativeLibraries.mapTo(sortedSetOf()) { "base/lib/$ABI/$it" }
    }

    object Artifacts {
        const val DEBUG_APK = "codex-mobile-debug.apk"
        const val DEBUG_TEST_APK = "codex-mobile-debug-androidTest.apk"
        const val RELEASE_APK = "app-release.apk"
        const val RELEASE_BUNDLE = "app-release.aab"
        const val MAPPING = "mapping.txt"
        const val SMOKE_DIRECTORY = "smoke-artifacts"
        const val SBOM = "docs/technical/sbom.cdx.json"

        fun smoke(layout: ProjectLayout): Provider<Directory> =
            layout.buildDirectory.dir(SMOKE_DIRECTORY)

        fun configuredSmoke(layout: ProjectLayout, path: Provider<String>): Provider<Directory> =
            path.map(layout.projectDirectory::dir)

        fun reproducibility(layout: ProjectLayout): Directory =
            layout.projectDirectory.dir(".gradle/reproducibility")

        fun appSmokeFromRoot(layout: ProjectLayout): Directory =
            layout.projectDirectory.dir("build/modules/android/app/$SMOKE_DIRECTORY")
    }

    object Properties {
        const val VERSION_CODE = "codexMobile.versionCode"
        const val VERSION_NAME = "codexMobile.versionName"
        const val CODEX_VERSION = "codexMobile.codexVersion"
        const val CODEX_ARCHIVE_SHA256 = "codexMobile.codexArchiveSha256"
        const val CODEX_BINARY_SHA256 = "codexMobile.codexBinarySha256"
        const val RELEASE_STORE_FILE = "codexMobile.release.storeFile"
        const val RELEASE_STORE_PASSWORD = "codexMobile.release.storePassword"
        const val RELEASE_KEY_ALIAS = "codexMobile.release.keyAlias"
        const val RELEASE_KEY_PASSWORD = "codexMobile.release.keyPassword"
        const val SMOKE_MODE = "codexMobile.androidSmokeMode"
        const val SMOKE_ARTIFACTS = "codexMobile.smokeArtifacts"

        const val ENV_RELEASE_STORE_FILE = "CODEX_MOBILE_RELEASE_STORE_FILE"
        const val ENV_RELEASE_STORE_PASSWORD = "CODEX_MOBILE_RELEASE_STORE_PASSWORD"
        const val ENV_RELEASE_KEY_ALIAS = "CODEX_MOBILE_RELEASE_KEY_ALIAS"
        const val ENV_RELEASE_KEY_PASSWORD = "CODEX_MOBILE_RELEASE_KEY_PASSWORD"
        const val ENV_ANDROID_SERIAL = "ANDROID_SERIAL"
    }

    object Tasks {
        const val UPDATE_SBOM = "updateSbom"
        const val VERIFY_SBOM = "verifySbom"
        const val VERIFY_REPOSITORY = "verifyRepository"
        const val RELEASE_LOCAL = "releaseLocal"
        const val INSTALL_PHONE = "installPhone"
        const val STAGE_SMOKE = "stageSmokeArtifacts"
        const val ANDROID_SMOKE = "androidDeviceSmoke"
        const val NATIVE_SMOKE = "nativeRuntimeSmoke"
        const val VERIFY_RELEASE = "verifyRelease"
        const val CAPTURE_BASELINE = "captureReleaseBaseline"
        const val VERIFY_REPRODUCIBILITY = "verifyReleaseReproducibility"
        const val APP = ":android:app"
    }
}

enum class AndroidSmokeMode {
    FULL,
    PLATFORM;

    companion object {
        fun parse(value: String): AndroidSmokeMode = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "unknown Android smoke mode '$value'; expected ${entries.joinToString { it.name.lowercase() }}",
        )
    }
}

enum class AndroidSmokeCase(
    val selector: String,
    val modes: Set<AndroidSmokeMode>,
) {
    PACKAGING_CHECKSUM(
        "io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#runtimePackagingPreparationAndChecksum",
        AndroidSmokeMode.entries.toSet(),
    ),
    PROCESS_LIFECYCLE(
        "io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#processStartStopRestartAndUnexpectedExit",
        setOf(AndroidSmokeMode.FULL),
    ),
    PLATFORM_PRIVACY(
        "io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#runtimeCredentialsComponentsAndLogsRemainPrivate",
        setOf(AndroidSmokeMode.PLATFORM),
    );

    companion object {
        fun forMode(mode: AndroidSmokeMode): List<AndroidSmokeCase> = entries.filter { mode in it.modes }
    }
}
