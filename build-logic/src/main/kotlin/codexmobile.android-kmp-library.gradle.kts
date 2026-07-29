import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(17)
    compilerOptions.optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    compilerOptions.optIn.add("kotlin.time.ExperimentalTime")

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
