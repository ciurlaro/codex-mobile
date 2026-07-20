# Codex Mobile

An Android feasibility project for running Codex locally, while Android keeps final authority over device data and side effects.

**Status:** [Steps 01–05](docs/roadmap/README.md) are complete; [Step 06](docs/roadmap/06-mvp-readiness.md) is the active experiment.

## Ownership

| Owner | Responsibility |
|---|---|
| Codex | Provider conversation semantics; never the truth about Android operations |
| Android | Permissions, resource access, process mechanics, and side effects |
| Core | Coordination, policy, approval enforcement, and recovery semantics |
| UI | Presentation and trustworthy user approval |

## Modules

| Module | Contains |
|---|---|
| `:app:android` | Compose UI, ViewModels, composition root, visible lifecycle |
| `:core` | Provider-neutral contracts, coordination, policy |
| `:agent:codex` | `CodexAgentClient`, app-server protocol and authentication |
| `:platform:android` | SAF, Android tools, process launch, persistence |

Start with [requirements](docs/requirements.md), then read [architecture](docs/architecture.md), [objects](docs/objects.md), [decisions](docs/decisions.md), and the [roadmap](docs/roadmap/README.md).

## Verification

```sh
bash scripts/verify-structure.sh
gradle test assembleDebug assembleDebugAndroidTest
```

CI pins the required Gradle version. The debug build downloads the pinned Codex app-server release, verifies both archive and executable checksums, and packages the ARM64 executable as a native library.
