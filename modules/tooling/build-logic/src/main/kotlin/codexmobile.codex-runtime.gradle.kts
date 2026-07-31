tasks.register<PrepareCodexRuntimeTask>(CodexMobileAutomation.Tasks.PREPARE_RUNTIME) {
    codexVersion.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_VERSION))
    archiveSha256.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_ARCHIVE_SHA256))
    binarySha256.set(providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_BINARY_SHA256))
    localArchive.set(
        providers.gradleProperty(CodexMobileAutomation.Properties.CODEX_ARCHIVE_FILE)
            .map { layout.projectDirectory.file(it) },
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/codex-runtime/main"))
}
