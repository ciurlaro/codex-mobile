# Codex Mobile

Codex Mobile is an Android host for the pinned Codex App Server `0.145.0`.
It supports ChatGPT authentication, conversations, streaming, Plan mode,
approvals, hooks, official App Server plugins and skills, workspace selection,
Markdown and native math rendering, and foreground execution.

The repository builds independently from fixed Maven Central artifacts. It
does not require a companion checkout, ship an extension-hosting framework,
download executable extensions, or use a remote-runtime fallback.

## Modules

Every Gradle module is under the root [`modules`](modules) directory.

| Gradle path | Location | Responsibility |
|---|---|---|
| `:multiplatform:codex-shared` | `modules/multiplatform/codex-shared` | Product state, persistence, session orchestration, and Compose UI; consumes `codex-agent-client:0.1.0` |
| `:android:app` | `modules/android/app` | Android entry points, permissions, workspace, service, packaging, and RaTeX bridge; consumes `codex-agent-runtime-android:0.1.0` |
| included build | `modules/tooling/build-logic` | Convention plugins and typed build tasks |

Production shared code is physically `commonMain`-only. The reusable agent,
protocol, and Android runtime are published by `ciurlaro/codex-agent`.

## Verification

```sh
./gradlew verifyRepository check testDebugUnitTest assembleDebug assembleDebugAndroidTest lint
```

The checked-in wrapper pins Gradle 9.4.1. See
[architecture](docs/technical/architecture.md),
[security](docs/technical/security.md),
[privacy](docs/technical/privacy.md), and
[release operations](docs/technical/release.md).

## Licence

Codex Mobile is distributed under `GPL-3.0-or-later`. Packaged third-party
components retain their own licences.
