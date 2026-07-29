plugins {
    id("codexmobile.android-kmp-library")
    `maven-publish`
}

group = "io.github.ciurlaro.codexmobile"
version = libs.versions.extension.provider.api.get()

kotlin {
    android { namespace = "io.github.ciurlaro.codexmobile.provider.api" }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
