plugins {
    alias(libs.plugins.android.dynamic.feature)
}

android {
    namespace = "io.github.ciurlaro.codexmobile.features.documents"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":app:android"))
    implementation("io.github.ciurlaro.codexmobile.providers:documents-android:1.0.0")
}
