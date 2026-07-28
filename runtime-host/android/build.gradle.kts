plugins {
    id("codexmobile.android-library")
}

android {
    namespace = "io.github.ciurlaro.codexmobile.appserver.host.android"
}

dependencies {
    api(project(":runtime-host"))
    implementation(libs.codex.app.server.client)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.bundles.android.test)
}
