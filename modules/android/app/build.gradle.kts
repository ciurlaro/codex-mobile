plugins {
    id("codexmobile.android-app-automation")
}

dependencies {
    implementation(project(":multiplatform:codex-shared"))
    implementation(libs.codex.agent.runtime.android)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.markdown.material3)
    implementation(libs.okio)
    implementation(libs.ratex.android)

    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.bundles.android.test)
}
