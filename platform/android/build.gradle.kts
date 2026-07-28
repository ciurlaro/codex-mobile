plugins {
    id("codexmobile.android-library")
}

android {
    namespace = "io.github.ciurlaro.codexmobile.platform.android"
}

dependencies {
    implementation(project(":runtime-host:android"))
    implementation(libs.codex.app.server.client)
    implementation(project(":core"))
    implementation(project(":agent:codex"))
    implementation(libs.codex.provider.api)
    implementation(libs.documents.provider.android)
    implementation(libs.telegram.provider.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.bundles.android.test)
}
