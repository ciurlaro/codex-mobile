# Android host release and operations

## Supported host

- ARM64 (`arm64-v8a`) stock Android API 26–37.
- One ChatGPT account through Codex-managed browser authentication.
- Android **All files access** plus one selected shared-storage directory used as each turn's starting `cwd`.
- Standard GitHub marketplace plugins and the bundled official Android providers.
- Ordinary shell work through App Server, four approval policies, model speed tiers, and foreground execution.
- Typed mutations under Auto review fall back to explicit one-use user approval on App Server `0.145.0`.

The host release checks out the exact `codexMobile.providerRevision` and bundles its Documents and Telegram libraries in the base APK. A full APK update removes legacy provider splits while preserving application data.

## CI build and verification

Java 17, the checked-in Gradle 9.4.1 wrapper, Android platform 37, build-tools 36.0.0, and NDK 29.0.14206865 are required.

```sh
export CODEX_MOBILE_RELEASE_STORE_FILE=/absolute/path/to/keystore
export CODEX_MOBILE_RELEASE_STORE_PASSWORD=...
export CODEX_MOBILE_RELEASE_KEY_ALIAS=...
export CODEX_MOBILE_RELEASE_KEY_PASSWORD=...
export ANDROID_HOME=/absolute/path/to/android-sdk
./gradlew test assembleDebug assembleDebugAndroidTest lint assembleRelease bundleRelease
scripts/verify-release.sh
scripts/verify-reproducible-release.sh
```

GitHub pull-request CI runs these commands with an ephemeral key. Official releases reuse the verified artifacts and re-sign them in the manually approved `release` environment; no Gradle or repository script runs while the production key is available. Fork and pull-request jobs receive no production secret.

`scripts/release-local.sh` is an explicit fallback when GitHub Actions is unavailable. It obtains the password from Keychain and runs the same release checks; `--reproducible` adds two clean byte-for-byte comparison builds.

`assembleRelease` refuses an unsigned APK. The verifier checks signature, manifest, pinned App Server and provider revision, locks, dependency verification, SBOM, bundled provider entry points, and the exact native-library set.

R8 keeps only JNI-bound names and the two metadata-compared provider entry-point names; the rest of the monolithic app remains optimizable.

## Install on a connected phone

```sh
scripts/install-phone.sh
```

The command verifies the signed release, performs a full in-place update with `adb install -r`, verifies that legacy provider splits are gone, and opens the app. It never uninstalls the app or clears data.

## Host provenance

| Input | Pinned evidence |
|---|---|
| Codex App Server | `0.145.0`; archive SHA-256 `3a185f6a1e2ec3ce7ebe9ea5ab23a81bfab75470337e66e235b881ca40ac8932`; ARM64 payload SHA-256 `9c5954b50520b68d7d181804965b554f09add95cc8fb0db6a7750111a1296b60` |
| Gradle | Wrapper/distribution `9.4.1` with checksums in `gradle/wrapper` |
| Dependencies | Strict lock files and `gradle/verification-metadata.xml` |
| Inventory | Deterministic CycloneDX 1.6 `docs/sbom.cdx.json`, checked by `scripts/generate-sbom.py --check` |

## Release gates

The release record covers startup, session readiness, first token, long streams, memory, CPU, file descriptors, threads, process activity, APK size/hash, supported Android versions, and marketplace/provider lifecycle drills. Before promotion, verify source addition, cache-first catalog refresh, restart-free activation/removal, next-chat tool visibility, ordinary shell work, all approval modes, mutation recovery without resubmission, official plugins, foreground behavior, and app-data erasure.

Provider publishing is separate. Each add-on manifest pins a matching host version, API range, schema digest, MCP names, split name, ABI set, artifact URL, and SHA-256. Its release checks prove signer compatibility, post-restart activation/removal, functional behavior, declared JNI/models, licences, size, network/download behavior, and absence of helper executables.
The coordinated provider release publishes a deterministic `release-manifest.json` that binds the exact host APK, App Server client/protocol/runtime, provider API and implementations, plugin content, feature APKs, MCP image, compatibility ranges, and both SBOMs by revision and SHA-256.
