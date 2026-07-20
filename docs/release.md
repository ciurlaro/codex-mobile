# Android MVP release and operations

## Supported product

- ARM64 (`arm64-v8a`) stock Android API 26–37.
- One ChatGPT account through Codex-managed browser authentication.
- Android `DocumentsProvider` trees selected with SAF.
- Scoped listing/read and one explicitly approved rename in a disposable writable tree.
- Debug and release APKs; additional providers, CPUs, iOS, KMP, shell, general automation, and runtime updating are unsupported.

## Reproducible signed build

Java 17, the checked-in Gradle 9.4.1 wrapper, Android platform 37, and build-tools 36.0.0 are required. On the configured developer Mac, one command retrieves the release password from macOS Keychain, builds every artifact, runs tests and lint, and verifies the signed APK:

```sh
scripts/release-local.sh
```

Add `--reproducible` to perform the two clean byte-for-byte comparison builds too. The password is inherited only by the build process; it is not printed or stored in the repository. For another workstation or CI, provide the signing material explicitly:

```sh
export CODEX_MOBILE_RELEASE_STORE_FILE=/absolute/path/to/keystore
export CODEX_MOBILE_RELEASE_STORE_PASSWORD=...
export CODEX_MOBILE_RELEASE_KEY_ALIAS=...
export CODEX_MOBILE_RELEASE_KEY_PASSWORD=...
export ANDROID_HOME=/absolute/path/to/android-sdk
./gradlew test assembleDebug assembleDebugAndroidTest lint assembleRelease
scripts/verify-release.sh
scripts/verify-reproducible-release.sh
```

`assembleRelease` refuses to emit an unsigned APK. Release code and resources are shrunk. The verifier checks the signature, version, manifest exposure, cleartext/backup policy, one ABI/runtime, upstream license/notice, lock files, verification metadata, R8 mapping, and current SBOM. The reproducibility check performs two clean, cache-disabled signed builds and requires byte-for-byte identical APKs.

AGP's encrypted Play Console SDK-dependency block is omitted from APKs because its randomized ciphertext prevents byte-for-byte reproduction. The release lockfile, strict verification metadata, and checked-in SBOM remain the dependency inventory; a Play-distributed build can restore the block only if the release policy replaces the byte-identical APK requirement.

## Install on a connected phone

Connect and unlock one physical Android phone with USB debugging authorized, then run:

```sh
scripts/install-phone.sh
```

The command performs the verified signed release above, updates the existing app in place with `adb install -r`, verifies the package, and opens it. It refuses emulators, unauthorized phones, and ambiguous multi-phone selections. When several phones are connected, set `ANDROID_SERIAL` explicitly. It never uninstalls the app or clears its data.

## Provenance

| Input | Pinned evidence |
|---|---|
| Codex app-server | `0.144.6`; archive SHA-256 `3539380f431aa72ce1e9ba83cf4d9b2c2a70d12ddf3280bc67c8c59f93bb9eb5`; ARM64 executable SHA-256 `09d6a41d6189b14317ec5d556251e5195e9a4235c28867fc75ee5c1d54be02cd` |
| Gradle | Wrapper/distribution `9.4.1` with official checksums in `gradle/wrapper` |
| Maven/plugins | Project lock files plus strict `gradle/verification-metadata.xml` SHA-256 verification |
| Inventory | Deterministic CycloneDX 1.6 `docs/sbom.cdx.json`, checked by `scripts/generate-sbom.py --check` |
| License | Upstream Apache-2.0 license and notice packaged under APK assets |

## Recorded budgets

| Measure | MVP budget / hard bound |
|---|---|
| Cold UI available | 5 seconds |
| Persisted-account session ready | 30 seconds on the stock test network |
| First streamed token | 60 seconds on the stock test network |
| Activity recreation stress | 20 recreations in 30 seconds; no more than 8 retained FDs or threads after warm-up |
| App plus runtime PSS | 192 MiB during the two-minute background profile; idle CPU below 1% |
| Visible streamed response | 256 Ki characters, then an explicit truncation marker |
| JSON-RPC message | 4 MiB |
| Tool arguments | 72 Ki characters |
| Document/list result | 64 KiB document; 2,048 entries and 512 KiB listing metadata |
| Mutation recovery | 64 pending plans/renames; 64 KiB recovery payload; resolved acknowledged rows retained 30 days |

The release record must contain measured startup/session/first-token, long-stream/listing, PSS/CPU, FD/thread/process/grant, APK size/hash, and API/device results. A budget failure blocks release; the budget is not raised to fit a failing build.

## Operator drills without sensitive logs

| Failure | User/operator diagnosis and recovery |
|---|---|
| Runtime crash/EOF | UI shows a bounded failure and stable diagnostic reference; foreground notification requests attention. Stop/restart is explicit; stale mutation/background markers reconcile without automatic restart. |
| Authentication failure/disabled device authorization | UI retains the bounded app-server error, an authentication diagnostic category, cancel control, and safe retry. The app opens only the validated official browser URL; account consent remains user-controlled. |
| Protocol mismatch | Invalid frames or server methods fail the client closed with `protocol_failure`, `-32601`, or `-32602`; no device tool executes. Restart after verifying the pinned runtime rather than ignoring the mismatch. |
| Provider/revoked grant/offline | UI reports the bounded Android/provider or network failure. Re-select/revoke scope, reconnect, or retry a fresh read. Unknown mutations remain visible and are never generically retried. |
| App crash report request | Collect Android's content-free crash category/stack and the visible diagnostic reference only. Never request credentials, codes, prompts, document content, Codex private files, or the runtime diagnostic database. |

The release operator verifies a clean stock install, user-controlled authentication, one read, one approved disposable rename, sign-out, app-data erasure, and an upgrade from version code 1 to 2 before distribution. Production signing/upload and publication are intentionally not performed by this repository's CI.
