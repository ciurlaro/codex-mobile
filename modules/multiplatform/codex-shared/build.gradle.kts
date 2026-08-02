plugins {
    id("codexmobile.android-kmp-serialization-library")
}

kotlin {
    android { namespace = "io.github.ciurlaro.codexmobile.runtime" }

    sourceSets {
        commonMain.dependencies {
            api(libs.codex.agent.client)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.markdown.material3)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
