# Android release and operations

> **Current status:** GitHub preview release only. Promotion to a production/store release still requires the stock-device, distribution-policy, native-license, hostile-file, emulator, and reproducible-release gates.

## Supported product

- ARM64 (`arm64-v8a`) stock Android API 26–37.
- One ChatGPT account through Codex-managed browser authentication.
- Android **All files access** plus one user-selected shared-storage directory used as each turn's starting `cwd`.
- Installed-by-default Documents and Telegram plugins using strict app-server dynamic tools; both can be disabled but not uninstalled.
- Private `mutool`, English-data `tesseract`, `officecli`, and `tgcli` backends invoked by fixed Android handlers and absent from Codex's shell `PATH`.
- Ordinary shell reads/writes/overwrite/copy/move/delete in shared storage; no duplicate generic Android file tools.
- Four user-selectable approval policies, model speed tiers, and browserless Telegram login. Typed Auto-review mutations are intentionally unavailable on app-server `0.144.6`.
- Debug and release APKs; additional CPUs, iOS, KMP, accessibility automation, and runtime updating are unsupported.

## Reproducible signed build

Java 17, Node/npm, CMake, Ninja, make, bsdtar, patch, patchelf, zip, the checked-in Gradle 9.4.1 wrapper, Android platform 37, build-tools 36.0.0, and NDK 29.0.14206865 are required. On the configured developer Mac, one command retrieves the release password from macOS Keychain, builds every artifact, runs tests and lint, and verifies the signed APK:

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

`assembleRelease` refuses to emit an unsigned APK. Release code and resources are shrunk. The verifier checks the signature, version, manifest exposure, cleartext/backup policy, one ABI/runtime, both built-in plugin manifests/skills, private backend assets, upstream license/notice, lock files, verification metadata, R8 mapping, and current SBOM. The reproducibility check performs two clean, cache-disabled signed builds and requires byte-for-byte identical APKs.

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
| Private native backends | Checksum-pinned MuPDF 1.28.0, Tesseract 5.5.2/Leptonica 1.87.0, OfficeCLI 1.0.139, tgcli 2.1.0 commit `649d937`, Node 24.17.0, and runtime libraries in `scripts/prepare-private-backends.sh`; the packaging step verifies the pinned Telegram single-submit/random-ID contract |
| Inventory | Deterministic CycloneDX 1.6 `docs/sbom.cdx.json`, checked by `scripts/generate-sbom.py --check` |
| License | Packaged third-party notices; MuPDF's AGPL-3.0 obligations are an explicit release gate |

## Release budgets

| Measure | Budget / hard bound |
|---|---|
| Cold UI available | 5 seconds |
| Persisted-account session ready | 30 seconds on the stock test network |
| First streamed token | 60 seconds on the stock test network |
| Activity recreation stress | 20 recreations in 30 seconds; no more than 8 retained FDs or threads after warm-up |
| App plus runtime PSS | 192 MiB during the two-minute background profile; idle CPU below 1% |
| Visible streamed response | 256 Ki characters, then an explicit truncation marker |
| JSON-RPC message | 4 MiB |
| Document input/extraction | 100 MiB input, 20 read pages, 5 English OCR pages, 4 viewed pages, 2 MiB private extraction/image result |
| Native backend run | Fixed command, bounded output, hard timeout, and device memory/storage watchdog during hostile-file tests |

The release record must contain measured startup/session/first-token, long-stream/listing, PSS/CPU, FD/thread/process/grant, APK size/hash, and API/device results. A budget failure blocks release; the budget is not raised to fit a failing build.

## Operator drills without sensitive logs

| Failure | User/operator diagnosis and recovery |
|---|---|
| Runtime crash/EOF | UI shows a bounded failure and stable diagnostic reference; foreground notification requests attention. Stop/restart is explicit. |
| Authentication failure/disabled device authorization | UI retains the bounded app-server error, an authentication diagnostic category, cancel control, and safe retry. The app opens only the validated official browser URL; account consent remains user-controlled. |
| Protocol mismatch | Invalid frames or server methods fail the client closed with `protocol_failure`, `-32601`, or `-32602`. Restart after verifying the pinned runtime rather than ignoring the mismatch. |
| Storage permission/workspace/offline | UI reports the bounded permission, file, or network failure. Restore all-files access, reselect the workspace, reconnect, or retry. |
| Indeterminate mutation | Do not retry automatically. Inspect the destination document or Telegram conversation; the journal will replay the same structured result for the same call ID. |
| App crash report request | Collect Android's content-free crash category/stack and the visible diagnostic reference only. Never request credentials, codes, prompts, document content, Codex private files, or the runtime diagnostic database. |

Before promoting a preview to production, the release operator verifies a clean stock install, all-files permission disclosure, workspace-to-turn `cwd`, ordinary shell create/overwrite/copy/move/delete, built-in plugin visibility/disablement, all four approval modes and the Auto-review mutation limitation, speed-tier selection, typed PDF extraction/rendering, bounded English OCR, transactional Office read/write, hostile-file failure behavior, browserless Telegram login/read/single-send/logout, crash recovery without resubmission, native commands absent from Codex `PATH`, official plugin use, Markdown/link handling, active-only notifications, keyboard resize, sign-out, app-data erasure, native licenses, and an in-place upgrade. Publication is intentionally not performed by CI.
