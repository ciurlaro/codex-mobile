plugins {
    id("codexmobile.kotlin-multiplatform")
    `maven-publish`
}

group = "io.github.ciurlaro.codexmobile"
version = libs.versions.app.server.client.get()

kotlin {
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}
