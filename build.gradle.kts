plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("visualCheck") {
    group = "verification"
    description = "Compare the nine app-owned visual scenarios with reviewed baselines."
    dependsOn(":app:android:connectedDebugAndroidTest")
}

tasks.register("visualCapture") {
    group = "verification"
    description = "Capture candidate visual PNGs on-device without accepting baselines."
    dependsOn(":app:android:connectedDebugAndroidTest")
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
