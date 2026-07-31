import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.File

plugins {
    id("codexmobile.android-application")
    id("codexmobile.codex-runtime")
}

val releaseStorePath = providers.gradleProperty(CodexMobileAutomation.Properties.RELEASE_STORE_FILE)
    .orElse(providers.environmentVariable(CodexMobileAutomation.Properties.ENV_RELEASE_STORE_FILE))
val releaseStorePassword = providers.gradleProperty(CodexMobileAutomation.Properties.RELEASE_STORE_PASSWORD)
    .orElse(providers.environmentVariable(CodexMobileAutomation.Properties.ENV_RELEASE_STORE_PASSWORD))
val releaseKeyAlias = providers.gradleProperty(CodexMobileAutomation.Properties.RELEASE_KEY_ALIAS)
    .orElse(providers.environmentVariable(CodexMobileAutomation.Properties.ENV_RELEASE_KEY_ALIAS))
val releaseKeyPassword = providers.gradleProperty(CodexMobileAutomation.Properties.RELEASE_KEY_PASSWORD)
    .orElse(providers.environmentVariable(CodexMobileAutomation.Properties.ENV_RELEASE_KEY_PASSWORD))
val releaseSigningConfigured = listOf(
    releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
).all { it.isPresent }
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val visualCaptureRequested = "visualCapture" in requestedTaskNames
val visualCheckRequested = "visualCheck" in requestedTaskNames
val appVersionCode = providers.gradleProperty(CodexMobileAutomation.Properties.VERSION_CODE).map(String::toInt)
val appVersionName = providers.gradleProperty(CodexMobileAutomation.Properties.VERSION_NAME)

extensions.configure<ApplicationExtension> {
    namespace = "io.github.ciurlaro.codexmobile.app"
    defaultConfig {
        applicationId = CodexMobileAutomation.App.APPLICATION_ID
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()
        if (visualCaptureRequested || visualCheckRequested) {
            testInstrumentationRunnerArguments["class"] =
                "io.github.ciurlaro.codexmobile.app.VisualRegressionTest"
            testInstrumentationRunnerArguments["captureOnly"] = visualCaptureRequested.toString()
        }
        ndk { abiFilters += CodexMobileAutomation.App.ABI }
    }
    dependenciesInfo { includeInApk = false }
    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseStorePath.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug" }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "**/${CodexMobileAutomation.App.RUNTIME_LIBRARY}"
            useLegacyPackaging = true
        }
    }
    bundle {
        abi { enableSplit = false }
        density { enableSplit = false }
        language { enableSplit = false }
    }
}

val prepareRuntime = tasks.named<PrepareCodexRuntimeTask>(CodexMobileAutomation.Tasks.PREPARE_RUNTIME)
val verifyReleaseSigning = tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
    configured.set(releaseSigningConfigured)
    storeFile.set(layout.file(releaseStorePath.map(::File)))
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
val aapt2 = androidComponents.sdkComponents.aapt2.flatMap { it.executable }
val adb = androidComponents.sdkComponents.adb
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareRuntime, PrepareCodexRuntimeTask::outputDirectory)
    val test = checkNotNull(variant.androidTest) { "debug variant must provide androidTest artifacts" }
    val stageSmoke = tasks.register<StageSmokeArtifactsTask>(CodexMobileAutomation.Tasks.STAGE_SMOKE) {
        appApkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
        testApkDirectory.set(test.artifacts.get(SingleArtifact.APK))
        outputDirectory.set(CodexMobileAutomation.Artifacts.smoke(layout))
    }
    tasks.register<AndroidDeviceSmokeTask>(CodexMobileAutomation.Tasks.ANDROID_SMOKE) {
        if (!providers.gradleProperty(CodexMobileAutomation.Properties.SMOKE_ARTIFACTS).isPresent) {
            dependsOn(stageSmoke)
        }
        adbExecutable.set(adb)
        artifactsDirectory.set(
            providers.gradleProperty(CodexMobileAutomation.Properties.SMOKE_ARTIFACTS)
                .map { layout.projectDirectory.dir(it) }
                .orElse(stageSmoke.flatMap { it.outputDirectory }),
        )
        mode.set(providers.gradleProperty(CodexMobileAutomation.Properties.SMOKE_MODE).orElse("full"))
    }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareRuntime, PrepareCodexRuntimeTask::outputDirectory)
    val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
    val verifyRelease = tasks.register<VerifyReleaseTask>(CodexMobileAutomation.Tasks.VERIFY_RELEASE) {
        dependsOn("assembleRelease", "bundleRelease", ":${CodexMobileAutomation.Tasks.VERIFY_SBOM}")
        this.apkDirectory.set(apkDirectory)
        bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
        mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
        aapt2Executable.set(aapt2)
        sbom.set(rootProject.layout.projectDirectory.file(CodexMobileAutomation.Artifacts.SBOM))
        lockfiles.from(listOf(
            "settings-gradle.lockfile", "modules/android/app/gradle.lockfile",
            "modules/multiplatform/codex-shared/gradle.lockfile",
            "modules/tooling/protocol-generator/gradle.lockfile",
            "modules/tooling/build-logic/gradle.lockfile",
            "modules/tooling/build-logic/settings-gradle.lockfile",
        ).map(rootProject.layout.projectDirectory::file))
        verificationMetadata.set(rootProject.layout.projectDirectory.file("gradle/verification-metadata.xml"))
        versionCode.set(appVersionCode)
        versionName.set(appVersionName)
        runtimeSha256.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_BINARY_SHA256))
    }
    tasks.register<CaptureReleaseBaselineTask>(CodexMobileAutomation.Tasks.CAPTURE_BASELINE) {
        dependsOn("assembleRelease")
        this.apkDirectory.set(apkDirectory)
        baseline.set(CodexMobileAutomation.Artifacts.reproducibility(rootProject.layout).file("release.apk"))
    }
    tasks.register<VerifyReleaseReproducibilityTask>(CodexMobileAutomation.Tasks.VERIFY_REPRODUCIBILITY) {
        dependsOn("assembleRelease")
        this.apkDirectory.set(apkDirectory)
        baseline.set(CodexMobileAutomation.Artifacts.reproducibility(rootProject.layout).file("release.apk"))
    }
    tasks.register<InstallPhoneTask>(CodexMobileAutomation.Tasks.INSTALL_PHONE) {
        dependsOn(verifyRelease)
        mustRunAfter(":${CodexMobileAutomation.Tasks.RELEASE_LOCAL}")
        adbExecutable.set(adb)
        releaseApk.set(apkDirectory.map { it.file(CodexMobileAutomation.Artifacts.RELEASE_APK) })
        requestedSerial.set(providers.environmentVariable(CodexMobileAutomation.Properties.ENV_ANDROID_SERIAL))
    }
}
