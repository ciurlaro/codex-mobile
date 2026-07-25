plugins {
    kotlin("multiplatform") version "2.3.10"
    `maven-publish`
}

group = "io.github.ciurlaro.codexmobile"
version = "2.0.0"

dependencyLocking { lockAllConfigurations() }

kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
