# Codex Mobile

An Android feasibility project for running Codex locally, while Android keeps final authority over device data and side effects.

**Status:** architecture frozen provisionally; implementation not started; [Step 01](docs/roadmap/01-runtime-premise.md) is the only active experiment.

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

CI pins the required Gradle version. The executable Codex runtime is intentionally absent until Step 01 determines how it can be packaged legally and reliably.
