require(project == rootProject) { "codexmobile.repository-verification must be applied to the root project" }

val verificationFiles = fileTree(layout.projectDirectory) {
    exclude(
        ".git/**", ".gradle/**", ".kotlin/**", ".idea/**", ".codex/**", "build/**",
        "**/build/**", "**/.gradle/**", "**/.kotlin/**", "codex-upstream/**", "captures/**",
        ".private/**", "local.properties", ".release-signing.local.md", "**/*.iml", "**/*.jks",
        "**/*.keystore", "**/.DS_Store",
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

val updateSbom = tasks.register<UpdateSbomTask>(CodexMobileAutomation.Tasks.UPDATE_SBOM) {
    dependencyLock.set(layout.projectDirectory.file("modules/android/app/gradle.lockfile"))
    versionName.set(providers.gradleProperty(CodexMobileAutomation.Properties.VERSION_NAME))
    codexVersion.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_VERSION))
    archiveSha256.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_ARCHIVE_SHA256))
    binarySha256.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_BINARY_SHA256))
    outputFile.set(layout.projectDirectory.file(CodexMobileAutomation.Artifacts.SBOM))
}

val verifySbom = tasks.register<VerifySbomTask>(CodexMobileAutomation.Tasks.VERIFY_SBOM) {
    dependencyLock.set(layout.projectDirectory.file("modules/android/app/gradle.lockfile"))
    versionName.set(providers.gradleProperty(CodexMobileAutomation.Properties.VERSION_NAME))
    codexVersion.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_VERSION))
    archiveSha256.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_ARCHIVE_SHA256))
    binarySha256.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_BINARY_SHA256))
    sbom.set(layout.projectDirectory.file(CodexMobileAutomation.Artifacts.SBOM))
}

tasks.register(CodexMobileAutomation.Tasks.VERIFY_REPOSITORY) {
    group = "verification"
    description = "Runs all deterministic repository-state verification."
    dependsOn(
        verifyStructure, verifySourceSize, verifySbom,
        ":multiplatform:codex-shared:verifyProtocolSource",
    )
}

tasks.register<NativeRuntimeSmokeTask>(CodexMobileAutomation.Tasks.NATIVE_SMOKE) {
    group = "verification"
    description = "Runs two native ARM App Server lifecycle cycles from the staged debug APK."
    if (!providers.gradleProperty(CodexMobileAutomation.Properties.SMOKE_ARTIFACTS).isPresent) {
        dependsOn("${CodexMobileAutomation.Tasks.APP}:${CodexMobileAutomation.Tasks.STAGE_SMOKE}")
    }
    artifactsDirectory.set(
        providers.gradleProperty(CodexMobileAutomation.Properties.SMOKE_ARTIFACTS)
            .map { layout.projectDirectory.dir(it) }
            .orElse(CodexMobileAutomation.Artifacts.appSmokeFromRoot(layout)),
    )
    expectedBinarySha256.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_BINARY_SHA256))
    certificateFile.fileValue(file("/etc/ssl/certs/ca-certificates.crt"))
}

tasks.register(CodexMobileAutomation.Tasks.RELEASE_LOCAL) {
    group = "build"
    description = "Builds and verifies the complete signed local release."
    gradle.includedBuilds.firstOrNull { it.name == "build-logic" }?.let { dependsOn(it.task(":test")) }
    dependsOn(
        CodexMobileAutomation.Tasks.VERIFY_REPOSITORY,
        ":tooling:protocol-generator:test",
        ":multiplatform:codex-shared:allTests",
        ":android:app:testDebugUnitTest",
        ":android:app:assembleDebug",
        ":android:app:assembleDebugAndroidTest",
        ":android:app:lint",
        ":android:app:${CodexMobileAutomation.Tasks.STAGE_SMOKE}",
        ":android:app:${CodexMobileAutomation.Tasks.VERIFY_RELEASE}",
    )
}

tasks.register(CodexMobileAutomation.Tasks.INSTALL_PHONE) {
    group = "install"
    description = "Builds, verifies, installs, and launches the signed release on one physical phone."
    dependsOn(CodexMobileAutomation.Tasks.RELEASE_LOCAL, ":android:app:${CodexMobileAutomation.Tasks.INSTALL_PHONE}")
}
