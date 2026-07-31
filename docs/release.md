# Android release and operations

## Supported host

- ARM64 (`arm64-v8a`) stock Android API 26–37.
- One ChatGPT account through Codex-managed browser authentication.
- Android **All files access** plus one selected workspace.
- Official App Server plugins, skills, hooks, Plan mode, approvals, Markdown,
  native math, and foreground execution.

## Build and verification

Java 17, the checked-in Gradle 9.4.1 wrapper, Android platform 37, and the
Android toolchain selected by the build are required.

```sh
export ANDROID_HOME=/absolute/path/to/android-sdk
./gradlew \
  :build-logic:test \
  verifyRepository \
  :tooling:protocol-generator:test \
  :multiplatform:codex-shared:allTests \
  :android:app:testDebugUnitTest \
  :android:app:assembleDebug \
  :android:app:assembleDebugAndroidTest \
  :android:app:lint
```

Release builds additionally require:

```sh
export CODEX_MOBILE_RELEASE_STORE_FILE=/absolute/path/to/keystore
export CODEX_MOBILE_RELEASE_STORE_PASSWORD=...
export CODEX_MOBILE_RELEASE_KEY_ALIAS=...
export CODEX_MOBILE_RELEASE_KEY_PASSWORD=...
./gradlew :android:app:assembleRelease :android:app:bundleRelease
scripts/verify-release.sh
```

Pull-request CI uses an ephemeral key for release-shape checks. Official
releases reuse verified artifacts and sign in a protected environment; no
Gradle task runs while the production key is exposed.

`assembleRelease` refuses missing signing configuration. Verification checks
the signature, manifest security flags, pinned runtime identity, dependency
locks and metadata, deterministic SBOM, native ABI payload, release mapping,
and reproducibility.

## Install on a connected phone

```sh
scripts/install-phone.sh
```

The command verifies and installs the signed release in place with
`adb install -r`; it never uninstalls the app or clears user data.

## Pinned provenance

| Input | Evidence |
|---|---|
| Codex App Server | Version and ARM64 archive/executable hashes in `gradle.properties` |
| Protocol | Schema, descriptors, provenance, and deterministic generated declarations under `modules/multiplatform/codex-shared/protocol` and `commonMain` |
| Gradle | Wrapper `9.4.1` and wrapper checksums |
| Dependencies | Strict module lock files and `gradle/verification-metadata.xml` |
| Inventory | Deterministic CycloneDX `docs/sbom.cdx.json` |
