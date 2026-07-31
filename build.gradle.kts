plugins {
    id("codexmobile.repository-verification")
}

tasks.register("visualCheck") {
    group = "verification"
    description = "Compare the nine app-owned visual scenarios with reviewed baselines."
    dependsOn(":android:app:connectedDebugAndroidTest")
}

tasks.register("visualCapture") {
    group = "verification"
    description = "Capture candidate visual PNGs on-device without accepting baselines."
    dependsOn(":android:app:connectedDebugAndroidTest")
}
