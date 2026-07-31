val runtimePreparationScript =
    rootProject.layout.projectDirectory.file("scripts/prepare-codex-runtime.sh")

tasks.register<PrepareCodexRuntimeTask>("prepareCodexRuntime") {
    codexVersion.set(providers.gradleProperty("codexMobile.codexVersion"))
    archiveSha256.set(providers.gradleProperty("codexMobile.codexArchiveSha256"))
    binarySha256.set(providers.gradleProperty("codexMobile.codexBinarySha256"))
    preparationScript.set(runtimePreparationScript)
    outputRuntime.set(
        layout.buildDirectory.file(
            "generated/codex-runtime/main/arm64-v8a/libcodex_app_server.so",
        ),
    )
}
