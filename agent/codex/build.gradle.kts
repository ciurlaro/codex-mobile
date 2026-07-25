plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("io.github.ciurlaro.codexmobile:app-server-client:0.144.6-1")
    implementation(project(":core"))
    implementation("io.github.ciurlaro.codexmobile:provider-api:2.0.0")
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
}
