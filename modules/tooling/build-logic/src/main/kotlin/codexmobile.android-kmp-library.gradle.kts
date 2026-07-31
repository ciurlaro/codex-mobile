import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

layout.buildDirectory.set(
    rootProject.layout.projectDirectory.dir(
        "build/modules/${project.path.removePrefix(":").replace(':', '/')}",
    ),
)

kotlin {
    jvmToolchain(17)

    android {
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencyLocking {
    lockAllConfigurations()
}
