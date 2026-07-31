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

tasks.register<PrepareCodexRuntimeTask>("prepareCodexX86Runtime") {
    codexVersion.set(providers.gradleProperty("codexMobile.codexVersion"))
    archiveSha256.set(providers.gradleProperty("codexMobile.codexX86ArchiveSha256"))
    binarySha256.set(providers.gradleProperty("codexMobile.codexX86BinarySha256"))
    target.set("x86_64-unknown-linux-musl")
    preparationScript.set(runtimePreparationScript)
    outputRuntime.set(
        layout.buildDirectory.file(
            "generated/codex-runtime/debug/x86_64/libcodex_app_server.so",
        ),
    )
}
