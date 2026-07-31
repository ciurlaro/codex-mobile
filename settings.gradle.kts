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
        google()
        mavenCentral()
    }
}

rootProject.name = "codex-mobile"

include(
    ":android:app",
    ":multiplatform:codex-shared",
    ":tooling:protocol-generator",
)

project(":android:app").projectDir = file("modules/android/app")
project(":android").projectDir = file("modules/android")
project(":multiplatform:codex-shared").projectDir =
    file("modules/multiplatform/codex-shared")
project(":multiplatform").projectDir = file("modules/multiplatform")
project(":tooling:protocol-generator").projectDir =
    file("modules/tooling/protocol-generator")
project(":tooling").projectDir = file("modules/tooling")
