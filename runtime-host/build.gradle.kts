plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "io.github.ciurlaro.codexmobile"
version = "0.144.6-1"

kotlin {
    jvm()
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
