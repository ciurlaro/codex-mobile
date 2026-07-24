plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

val bundletool by configurations.creating
dependencies { bundletool("com.android.tools.build:bundletool:1.18.3") }

tasks.register<JavaExec>("packageDebugProviderSplits") {
    dependsOn(":app:android:bundleDebug")
    val bundle = project(":app:android").layout.buildDirectory.file("outputs/bundle/debug/android-debug.aab")
    val archive = layout.buildDirectory.file("provider-splits/debug.apks")
    val keystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
    inputs.file(bundle)
    inputs.file(keystore)
    outputs.file(archive)
    classpath = bundletool
    mainClass.set("com.android.tools.build.bundletool.BundleToolMain")
    doFirst {
        archive.get().asFile.parentFile.mkdirs()
        val androidHome = System.getenv("ANDROID_HOME") ?: error("ANDROID_HOME is required")
        args(
            "build-apks",
            "--bundle=${bundle.get().asFile}",
            "--output=${archive.get().asFile}",
            "--overwrite",
            "--ks=$keystore",
            "--ks-pass=pass:android",
            "--ks-key-alias=androiddebugkey",
            "--key-pass=pass:android",
            "--aapt2=$androidHome/build-tools/36.0.0/aapt2",
        )
    }
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
