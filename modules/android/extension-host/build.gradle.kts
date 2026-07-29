plugins {
    id("codexmobile.android-library")
}

android {
    namespace = "io.github.ciurlaro.codexmobile.extension.host"
}

dependencies {
    implementation(project(":codex-agent-runtime"))
    implementation(libs.codex.extension.provider.api)
    implementation(libs.documents.provider.android)
    implementation(libs.telegram.provider.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.bundles.android.test)
}
