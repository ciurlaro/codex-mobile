plugins {
    id("codexmobile.kotlin-jvm")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
}
