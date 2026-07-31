import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity

require(project == rootProject) { "codexmobile.repository-verification must be applied to the root project" }

val verificationFiles = fileTree(layout.projectDirectory) {
    exclude(
        ".git/**",
        ".gradle/**",
        ".kotlin/**",
        ".idea/**",
        ".codex/**",
        "build/**",
        "**/build/**",
        "**/.gradle/**",
        "**/.kotlin/**",
        "codex-upstream/**",
        "captures/**",
        ".private/**",
        "local.properties",
        ".release-signing.local.md",
        "**/*.iml",
        "**/*.jks",
        "**/*.keystore",
        "**/.DS_Store",
    )
}

val verifyStructure = tasks.register<VerifyRepositoryStructureTask>("verifyStructure") {
    group = "verification"
    description = "Rejects legacy modules and production sources outside the agreed repository structure."
    repositoryRoot.set(layout.projectDirectory)
    repositoryFiles.from(verificationFiles)
}

val verifySourceSize = tasks.register<VerifySourceSizeTask>("verifySourceSize") {
    group = "verification"
    description = "Enforces handwritten and generated Kotlin source-size limits."
    repositoryRoot.set(layout.projectDirectory)
    sources.from(verificationFiles.matching { include("modules/**/*.kt", "modules/**/*.kts") })
}

val sbomScript = layout.projectDirectory.file("scripts/generate-sbom.py")
val verifySbom = tasks.register<Exec>("verifySbom") {
    group = "verification"
    description = "Rejects drift in the deterministic checked-in SBOM."
    workingDir(layout.projectDirectory)
    commandLine("python3", sbomScript.asFile.absolutePath, "--check")
    inputs.files(
        sbomScript,
        layout.projectDirectory.file("gradle.properties"),
        layout.projectDirectory.file("modules/android/app/gradle.lockfile"),
        layout.projectDirectory.file("docs/sbom.cdx.json"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register("verifyRepository") {
    group = "verification"
    description = "Runs all deterministic repository-state verification."
    dependsOn(
        verifyStructure,
        verifySourceSize,
        verifySbom,
        ":multiplatform:codex-shared:verifyProtocolSource",
    )
}
