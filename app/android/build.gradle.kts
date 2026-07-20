plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.ciurlaro.codexmobile.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.ciurlaro.codexmobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
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
    inputs.property("codexVersion", "0.144.6")
    inputs.property("archiveSha256", "3539380f431aa72ce1e9ba83cf4d9b2c2a70d12ddf3280bc67c8c59f93bb9eb5")
    inputs.property("binarySha256", "09d6a41d6189b14317ec5d556251e5195e9a4235c28867fc75ee5c1d54be02cd")
    outputs.file(codexRuntime)
    commandLine(
        rootProject.file("scripts/prepare-codex-runtime.sh"),
        "0.144.6",
        "3539380f431aa72ce1e9ba83cf4d9b2c2a70d12ddf3280bc67c8c59f93bb9eb5",
        "09d6a41d6189b14317ec5d556251e5195e9a4235c28867fc75ee5c1d54be02cd",
        codexRuntime.asFile.absolutePath,
    )
}

tasks.named("preBuild").configure {
    dependsOn(prepareCodexRuntime)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":agent:codex"))
    implementation(project(":platform:android"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)

    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
