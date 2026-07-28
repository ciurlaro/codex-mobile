plugins {
    id("codexmobile.kotlin-jvm")
}

dependencies {
    api(libs.codex.app.server.client)
    implementation(project(":core"))
    implementation(libs.codex.provider.api)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
}
