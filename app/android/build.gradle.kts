plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseStorePath = providers.gradleProperty("codexMobile.release.storeFile")
    .orElse(providers.environmentVariable("CODEX_MOBILE_RELEASE_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("codexMobile.release.storePassword")
    .orElse(providers.environmentVariable("CODEX_MOBILE_RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("codexMobile.release.keyAlias")
    .orElse(providers.environmentVariable("CODEX_MOBILE_RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("codexMobile.release.keyPassword")
    .orElse(providers.environmentVariable("CODEX_MOBILE_RELEASE_KEY_PASSWORD"))
val releaseSigningConfigured = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isPresent }
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val visualCaptureRequested = "visualCapture" in requestedTaskNames
val visualCheckRequested = "visualCheck" in requestedTaskNames
val appVersionCode = providers.gradleProperty("codexMobile.versionCode").map(String::toInt)
val appVersionName = providers.gradleProperty("codexMobile.versionName")
val codexVersion = providers.gradleProperty("codexMobile.codexVersion")
val codexArchiveSha256 = providers.gradleProperty("codexMobile.codexArchiveSha256")
val codexBinarySha256 = providers.gradleProperty("codexMobile.codexBinarySha256")
val privateBackendBundleVersion = providers.gradleProperty("codexMobile.privateBackendBundleVersion")
val privateBackendNdk = providers.gradleProperty("codexMobile.androidNdkPath")
    .orElse(providers.environmentVariable("ANDROID_NDK_HOME"))
    .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
    .orElse(
        providers.provider {
            val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            sdk?.let { "$it/ndk/29.0.14206865" } ?: "/opt/homebrew/share/android-ndk"
        },
    )

android {
    namespace = "io.github.ciurlaro.codexmobile.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.ciurlaro.codexmobile"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (visualCaptureRequested || visualCheckRequested) {
            testInstrumentationRunnerArguments["class"] =
                "io.github.ciurlaro.codexmobile.app.VisualRegressionTest"
            testInstrumentationRunnerArguments["captureOnly"] = visualCaptureRequested.toString()
        }

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }

    dependenciesInfo {
        includeInApk = false
    }

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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/libcodex_app_server.so"
            useLegacyPackaging = true
        }
    }
}

val codexRuntime = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libcodex_app_server.so",
)
val prepareCodexRuntime = tasks.register<Exec>("prepareCodexRuntime") {
    inputs.property("codexVersion", codexVersion)
    inputs.property("archiveSha256", codexArchiveSha256)
    inputs.property("binarySha256", codexBinarySha256)
    outputs.file(codexRuntime)
    commandLine(
        rootProject.file("scripts/prepare-codex-runtime.sh"),
        codexVersion.get(),
        codexArchiveSha256.get(),
        codexBinarySha256.get(),
        codexRuntime.asFile.absolutePath,
    )
}

val privateBackends = listOf(
    "libcodex_mutool.so",
    "libcodex_tesseract.so",
    "libcodex_officecli.so",
    "libcodex_officecli_musl.so",
    "libcodex_officecli_gcc.so",
    "libcodex_officecli_cxx.so",
    "libcodex_tgcli.so",
    "libcodex_node.so",
    "libcodex_z.so",
    "libcodex_cares.so",
    "libcodex_sqlite3.so",
    "libcodex_crypto.so",
    "libcodex_ssl.so",
    "libcodex_icudata.so",
    "libcodex_icui18n.so",
    "libcodex_icuuc.so",
    "libcodex_cxx.so",
).map { layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/$it") }
val privateBackendAssets = layout.projectDirectory.dir("src/main/assets/private-backends")
val preparePrivateBackends = tasks.register<Exec>("preparePrivateBackends") {
    inputs.property("bundleVersion", privateBackendBundleVersion)
    inputs.files(
        rootProject.file("scripts/prepare-private-backends.sh"),
        rootProject.file("scripts/native/tgcli-launcher.c"),
        rootProject.file("scripts/native/officecli-launcher.c"),
        rootProject.file("scripts/native/tgcli-package-lock.json"),
        rootProject.file("scripts/patches/tgcli-android.patch"),
        rootProject.file("scripts/patches/tesseract-android.patch"),
    )
    outputs.files(privateBackends)
    outputs.dir(privateBackendAssets)
    commandLine(
        rootProject.file("scripts/prepare-private-backends.sh"),
        privateBackendNdk.get(),
        layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a").asFile.absolutePath,
        privateBackendAssets.asFile.absolutePath,
    )
}

tasks.named("preBuild").configure {
    dependsOn(prepareCodexRuntime)
    dependsOn(preparePrivateBackends)
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    inputs.property("configured", releaseSigningConfigured)
    inputs.property("storePath", releaseStorePath.orElse(""))
    doLast {
        check(inputs.properties["configured"] == true) {
            "Release signing requires codexMobile.release.{storeFile,storePassword,keyAlias,keyPassword} " +
                "Gradle properties or the matching CODEX_MOBILE_RELEASE_* environment variables"
        }
        check(File(inputs.properties.getValue("storePath").toString()).isFile) {
            "Release keystore does not exist"
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":agent:codex"))
    implementation(project(":platform:android"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.markdown.material3)

    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
