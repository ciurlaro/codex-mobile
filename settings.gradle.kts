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
providerBuild?.let { includeBuild(it) }

include(
    ":app:android",
    ":core",
    ":agent:codex",
    ":platform:android",
    ":runtime-host",
    ":runtime-host:android",
)
if (providerBuild != null) {
    include(":provider_documents", ":provider_telegram")
    project(":provider_documents").projectDir = file("providers/documents")
    project(":provider_telegram").projectDir = file("providers/telegram")
}
