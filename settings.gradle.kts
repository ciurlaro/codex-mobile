pluginManagement {
    includeBuild("modules/tooling/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("codexAgent.repository").orNull?.let { repository ->
            maven {
                name = "codexAgentMigration"
                url = uri(repository)
                content { includeGroup("io.github.ciurlaro") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "codex-mobile"

include(
    ":android:app",
    ":multiplatform:codex-shared",
)

project(":android:app").projectDir = file("modules/android/app")
project(":android").projectDir = file("modules/android")
project(":multiplatform:codex-shared").projectDir =
    file("modules/multiplatform/codex-shared")
project(":multiplatform").projectDir = file("modules/multiplatform")
