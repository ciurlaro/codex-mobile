# Codex Mobile

An Android app for running Codex locally with a desktop-like shell in shared storage.

After the user grants Android **All files access**, a selected shared-storage directory becomes the `cwd` for every Codex turn. Codex keeps its normal shell for ordinary file work. Documents and Telegram are installed, user-toggleable built-in plugins whose strict dynamic tools are handled locally; their pinned native backends are private implementation details and are absent from Codex's `PATH`.

**Status:** GitHub preview. Production distribution requires the stock-device, licensing, and distribution-policy gates in the release guide.

## Ownership

| Owner | Responsibility |
|---|---|
| Codex | Provider conversation semantics; never the truth about Android operations |
| Android | Storage authority, app-server process mechanics, typed plugin execution, and private native integrations |
| Core | Provider-neutral agent contracts |
| UI | Presentation and user-selected runtime policy |

## Modules

| Module | Contains |
|---|---|
| `:app:android` | Compose UI, ViewModels, composition root, visible lifecycle |
| `:core` | Provider-neutral agent contracts |
| `:agent:codex` | `CodexAgentClient`, app-server protocol, dynamic-tool authority, and authentication |
| `:platform:android` | Shared-storage workspace, private native handlers, Telegram login, process launch, and mutation journal |

Start with [requirements](docs/requirements.md), then read [architecture](docs/architecture.md), [objects](docs/objects.md), and [decisions](docs/decisions.md).

## Verification

```sh
bash scripts/verify-structure.sh
./gradlew test assembleDebug assembleDebugAndroidTest lint
```

The checked-in wrapper pins Gradle 9.4.1. The build downloads checksum-pinned ARM64 runtime inputs and packages the Codex app-server plus its private native backends. Signed release construction, inspection, reproducibility, provenance, and operator drills are documented in [Android release and operations](docs/release.md); the trust and data contracts are in [security](docs/security.md) and [privacy](docs/privacy.md).

Browserless Telegram login needs one application API ID/hash supplied by the builder. Put them in the gitignored `local.properties` (or the matching environment variables); end users then enter only their phone number, Telegram code, and optional 2FA password:

```properties
codexMobile.telegram.apiId=123456
codexMobile.telegram.apiHash=replace-with-the-app-api-hash
```
