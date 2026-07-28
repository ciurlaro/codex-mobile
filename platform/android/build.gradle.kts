plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.ciurlaro.codexmobile.platform.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":runtime-host:android"))
    implementation("io.github.ciurlaro.codexmobile:app-server-client:0.145.0-1")
    implementation(project(":core"))
    implementation(project(":agent:codex"))
    implementation("io.github.ciurlaro.codexmobile:provider-api:2.0.0")
    implementation("io.github.ciurlaro.codexmobile.providers:documents-android:1.0.0")
    implementation("io.github.ciurlaro.codexmobile.providers:telegram-android:1.0.0")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
