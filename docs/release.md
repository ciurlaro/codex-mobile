# Android host release and operations

## Supported host

- ARM64 (`arm64-v8a`) stock Android API 26–37.
- One ChatGPT account through Codex-managed browser authentication.
- Android **All files access** plus one selected shared-storage directory used as each turn's starting `cwd`.
- Standard GitHub marketplace plugins and explicitly confirmed signed feature splits.
- Ordinary shell work through App Server, four approval policies, model speed tiers, and foreground execution.
- Typed Auto-review mutations remain unavailable on App Server `0.144.6`.

The host release is independent of any provider checkout. The base APK contains App Server and host code only. Provider repositories build and publish exact-host-version feature APKs separately with the same package name, version code, and signing certificate.

An Android package update cannot mix a new base version with old-version feature splits. Release tooling includes matching signed replacements for every installed provider in the same update session, or requires the user to finish provider removal before a base-only update. A later in-app repair cannot make an otherwise invalid package transaction safe.

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

`assembleRelease` refuses an unsigned APK. The verifier checks signature, manifest, pinned App Server, locks, dependency verification, SBOM, and that the base packages no provider definitions, models, feature code, or native payloads beyond App Server and AndroidX's declared graphics-path library. Provider-specific ABI, JNI/model size, licence, network, retry, and runtime-download audits belong to each provider release.

Provider release builds keep the project-owned provider API names stable and disable R8 name obfuscation for feature splits while retaining shrinking and optimization. Release verification rejects short default-package class descriptors and requires each split to reference the stable provider API.

## Install on a connected phone

```sh
scripts/install-phone.sh
```

The command verifies the signed release, updates a base-only installation on the selected physical device with `adb install -r`, verifies the package, and opens it. It refuses emulators, unauthorized devices, ambiguous multi-device selections, and installations containing feature splits. It never uninstalls the app or clears data.

## Host provenance

| Input | Pinned evidence |
|---|---|
| Codex App Server | `0.144.6`; archive SHA-256 `3539380f431aa72ce1e9ba83cf4d9b2c2a70d12ddf3280bc67c8c59f93bb9eb5`; ARM64 payload SHA-256 `09d6a41d6189b14317ec5d556251e5195e9a4235c28867fc75ee5c1d54be02cd` |
| Gradle | Wrapper/distribution `9.4.1` with checksums in `gradle/wrapper` |
| Dependencies | Strict lock files and `gradle/verification-metadata.xml` |
| Inventory | Deterministic CycloneDX 1.6 `docs/sbom.cdx.json`, checked by `scripts/generate-sbom.py --check` |

## Release gates

The release record covers startup, session readiness, first token, long streams, memory, CPU, file descriptors, threads, process activity, APK size/hash, supported Android versions, and marketplace/package lifecycle drills. Before promotion, verify source addition, cache-first catalog refresh, installation confirmation and restart continuation, disable/re-enable/uninstall, existing-thread notices, ordinary shell work, all approval modes, mutation recovery without resubmission, official plugins, foreground behavior, and app-data erasure.

Provider publishing is separate. Each add-on manifest pins a matching host version, API range, schema digest, MCP names, split name, ABI set, artifact URL, and SHA-256. Its release checks prove signer compatibility, post-restart activation/removal, functional behavior, declared JNI/models, licences, size, network/download behavior, and absence of helper executables.
The coordinated provider release publishes a deterministic `release-manifest.json` that binds the exact host APK, App Server client/protocol/runtime, provider API and implementations, plugin content, feature APKs, MCP image, compatibility ranges, and both SBOMs by revision and SHA-256.
