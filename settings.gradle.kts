pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "codex-mobile"

includeBuild("modules/multiplatform/extension-provider-api")

val providerBuild = providers.gradleProperty("codexMobile.providerBuild").orNull
    ?: sequenceOf(file("codex-mobile-plugins"), file("../codex-mobile-plugins"))
        .firstOrNull(File::isDirectory)?.absolutePath
    ?: error("codex-mobile-plugins is required to build the bundled providers")
includeBuild(providerBuild) { name = "codex-mobile-plugins" }

include(
    ":app",
    ":codex-agent-runtime",
    ":extension-host",
    ":tools:protocol-generator",
)

project(":app").projectDir = file("modules/android/app")
project(":codex-agent-runtime").projectDir = file("modules/multiplatform/codex-agent-runtime")
project(":extension-host").projectDir = file("modules/android/extension-host")
