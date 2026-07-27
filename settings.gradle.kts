pluginManagement {
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

includeBuild("app-server-client")
includeBuild("provider-api")

val providerBuild = providers.gradleProperty("codexMobile.providerBuild").orNull
    ?: sequenceOf(file("codex-mobile-plugins"), file("../codex-mobile-plugins"))
        .firstOrNull(File::isDirectory)?.absolutePath
    ?: error("codex-mobile-plugins is required to build the bundled providers")
includeBuild(providerBuild) { name = "codex-mobile-plugins" }

include(
    ":app:android",
    ":core",
    ":agent:codex",
    ":platform:android",
    ":runtime-host",
    ":runtime-host:android",
)
