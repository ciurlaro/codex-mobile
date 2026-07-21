import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use { load(it) }
}
val telegramApiId = providers.gradleProperty("codexMobile.telegram.apiId")
    .orElse(providers.environmentVariable("CODEX_MOBILE_TELEGRAM_API_ID"))
    .orElse(localProperties.getProperty("codexMobile.telegram.apiId", ""))
    .orElse("")
val telegramApiHash = providers.gradleProperty("codexMobile.telegram.apiHash")
    .orElse(providers.environmentVariable("CODEX_MOBILE_TELEGRAM_API_HASH"))
    .orElse(localProperties.getProperty("codexMobile.telegram.apiHash", ""))
    .orElse("")

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "io.github.ciurlaro.codexmobile.platform.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TELEGRAM_API_ID", telegramApiId.get().asBuildConfigString())
        buildConfigField("String", "TELEGRAM_API_HASH", telegramApiHash.get().asBuildConfigString())
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
