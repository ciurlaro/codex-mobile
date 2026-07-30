import java.io.File

plugins {
    id("codexmobile.android-application")
    id("codexmobile.codex-runtime")
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
val codexX86RuntimeDirectory = layout.buildDirectory.dir("generated/codex-runtime/debug")
android {
    namespace = "io.github.ciurlaro.codexmobile.app"
    defaultConfig {
        applicationId = "io.github.ciurlaro.codexmobile"
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()
        if (visualCaptureRequested || visualCheckRequested) {
            testInstrumentationRunnerArguments["class"] =
                "io.github.ciurlaro.codexmobile.app.VisualRegressionTest"
            testInstrumentationRunnerArguments["captureOnly"] = visualCaptureRequested.toString()
        }

        ndk {
            abiFilters += "arm64-v8a"
        }
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
        debug {
            applicationIdSuffix = ".debug"
            ndk {
                abiFilters += "x86_64"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "provider-addon-rules.pro",
            )
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/libcodex_app_server.so"
            useLegacyPackaging = true
        }
    }

    sourceSets.getByName("debug").jniLibs.directories.add(
        codexX86RuntimeDirectory.get().asFile.absolutePath,
    )

    bundle {
        abi { enableSplit = false }
        density { enableSplit = false }
        language { enableSplit = false }
    }
}

tasks.named("preBuild").configure {
    dependsOn("prepareCodexRuntime")
}

tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn("prepareCodexX86Runtime")
}

val verifyReleaseSigning = tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
    configured.set(releaseSigningConfigured)
    storeFile.set(layout.file(releaseStorePath.map(::File)))
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(project(":codex-agent-runtime"))
    implementation(project(":extension-host"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.sqlite.framework)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.markdown.material3)
    implementation(libs.ratex.android)

    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.bundles.android.test)
    androidTestImplementation(libs.codex.extension.provider.api)
}
