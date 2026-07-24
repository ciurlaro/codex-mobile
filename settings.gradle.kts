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

include(
    ":app:android",
    ":core",
    ":agent:codex",
    ":platform:android",
)

providers.gradleProperty("codexMobile.providerProjects").orNull
    ?.split('|')
    ?.filter(String::isNotBlank)
    .orEmpty()
    .forEach { directory ->
        val projectDirectory = file(directory).canonicalFile
        require(projectDirectory.isDirectory) { "Provider project does not exist: $projectDirectory" }
        val splitName = "provider_${projectDirectory.name.replace('-', '_')}"
        require(splitName.matches(Regex("[a-z][a-z0-9_]{0,79}"))) { "Invalid provider project name: $splitName" }
        val path = ":$splitName"
        require(findProject(path) == null) { "Duplicate provider project name: $splitName" }
        include(path)
        project(path).projectDir = projectDirectory
    }
