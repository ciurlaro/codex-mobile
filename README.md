# Codex Mobile

An Android app for running Codex locally with a desktop-like shell in shared storage.

After the user grants Android **All files access**, a selected shared-storage directory becomes the `cwd` for every Codex turn. Codex uses its normal shell for file work and finds `mutool`, `tesseract`, `officecli`, and `tgcli` on `PATH`; there is no parallel Android document-tool protocol.

**Status:** the original bounded MVP completed [Roadmap Steps 01–06](docs/roadmap/README.md). The all-files/native-tool replacement is distributed as a GitHub preview until its stock-device, licensing, and distribution-policy gates pass.

## Ownership

| Owner | Responsibility |
|---|---|
| Codex | Provider conversation semantics; never the truth about Android operations |
| Android | Storage permission, process mechanics, and native integrations |
| Core | Provider-neutral agent contracts |
| UI | Presentation and user-selected runtime policy |

## Modules

| Module | Contains |
|---|---|
| `:app:android` | Compose UI, ViewModels, composition root, visible lifecycle |
| `:core` | Provider-neutral agent contracts |
| `:agent:codex` | `CodexAgentClient`, app-server protocol and authentication |
| `:platform:android` | Shared-storage workspace, bundled CLI runtime, Telegram login, process launch |

Start with [requirements](docs/requirements.md), then read [architecture](docs/architecture.md), [objects](docs/objects.md), [decisions](docs/decisions.md), and the [roadmap](docs/roadmap/README.md).

## Verification

```sh
bash scripts/verify-structure.sh
./gradlew test assembleDebug assembleDebugAndroidTest lint
```

The checked-in wrapper pins Gradle 9.4.1. The build downloads checksum-pinned ARM64 runtime inputs and builds/packages the Codex app-server plus the native CLI bundle. Signed release construction, inspection, reproducibility, provenance, and operator drills are documented in [Android MVP release and operations](docs/release.md); the trust and data contracts are in [security](docs/security.md) and [privacy](docs/privacy.md).

Browserless Telegram login needs one application API ID/hash supplied by the builder. Put them in the gitignored `local.properties` (or the matching environment variables); end users then enter only their phone number, Telegram code, and optional 2FA password:

```properties
codexMobile.telegram.apiId=123456
codexMobile.telegram.apiHash=replace-with-the-app-api-hash
```
