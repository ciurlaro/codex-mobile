plugins {
    id("codexmobile.kotlin-multiplatform")
    `maven-publish`
}

group = "io.github.ciurlaro.codexmobile"
version = libs.versions.provider.api.get()

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
