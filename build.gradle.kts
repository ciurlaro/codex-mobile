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
