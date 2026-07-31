import org.gradle.api.artifacts.ExternalModuleDependency
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("codexmobile.android-app-automation")
}

val bundledSqliteTest = dependencies.create(libs.androidx.sqlite.bundled.get()) as ExternalModuleDependency
bundledSqliteTest.attributes {
    attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
}

dependencies {
    implementation(project(":multiplatform:codex-shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.markdown.material3)
    implementation(libs.okio)
    implementation(libs.ratex.android)

    testImplementation(kotlin("test-junit"))
    testImplementation(bundledSqliteTest)
    androidTestImplementation(libs.bundles.android.test)
}
