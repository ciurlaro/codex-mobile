plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.ciurlaro.codexmobile.appserver.host.android"
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
    api(project(":runtime-host"))
    implementation("io.github.ciurlaro.codexmobile:app-server-client:0.145.0-1")
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
