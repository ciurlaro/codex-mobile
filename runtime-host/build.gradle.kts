plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "io.github.ciurlaro.codexmobile"
version = "0.145.0-1"

kotlin {
    jvm()
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
