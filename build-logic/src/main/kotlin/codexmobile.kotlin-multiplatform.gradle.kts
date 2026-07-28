plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm()
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
