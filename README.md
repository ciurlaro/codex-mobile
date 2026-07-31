# Codex Mobile

Codex Mobile is an Android host for the pinned Codex App Server `0.145.0`.
It supports ChatGPT authentication, conversations, streaming, Plan mode,
approvals, hooks, official App Server plugins and skills, workspace selection,
Markdown and native math rendering, and foreground execution.

The repository builds independently. It does not require a companion
repository, ship an extension-hosting framework, download executable
extensions, or use a remote-runtime fallback.

## Modules

Every Gradle module is under the root [`modules`](modules) directory.

| Gradle path | Location | Responsibility |
|---|---|---|
| `:multiplatform:codex-shared` | `modules/multiplatform/codex-shared` | Common protocol, runtime policy, agent, application state, persistence, and Compose UI |
| `:android:app` | `modules/android/app` | Android entry points, permissions, paths, SQLite driver, Java process/socket mechanisms, service, and RaTeX bridge |
| `:tooling:protocol-generator` | `modules/tooling/protocol-generator` | Deterministic protocol generation |
| included build | `modules/tooling/build-logic` | Convention plugins and typed build tasks |

Production shared code is physically `commonMain`-only. Android supplies
unavoidable mechanisms; portable behavior and policy remain shared.

## Verification

```sh
bash scripts/verify-structure.sh
./gradlew check testDebugUnitTest assembleDebug assembleDebugAndroidTest lint
```

The checked-in wrapper pins Gradle 9.4.1. See
[architecture](docs/architecture.md), [security](docs/security.md),
[privacy](docs/privacy.md), and [release operations](docs/release.md).

## Licence

Codex Mobile is distributed under `GPL-3.0-or-later`. Packaged third-party
components retain their own licences.
