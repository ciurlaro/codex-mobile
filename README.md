# Codex Mobile

An Android feasibility project for running Codex locally, while Android keeps final authority over device data and side effects.

Selected Android documents can be read by content signature (text, PDF/OCR, images, DOCX, PPTX, XLSX, and CSV). Generated text is created or replaced transactionally in an app-private workspace, then exported to a separately selected Android folder only after an exact diff approval.

**Status:** [Roadmap Steps 01–06](docs/roadmap/README.md) are complete; the bounded Android MVP passes its documented release gates.

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
./gradlew test assembleDebug assembleDebugAndroidTest lint
```

The checked-in wrapper pins Gradle 9.4.1. The build downloads the pinned Codex app-server release, verifies both archive and executable checksums, and packages the ARM64 executable as a native library. Signed release construction, inspection, reproducibility, provenance, and operator drills are documented in [Android MVP release and operations](docs/release.md); the trust and data contracts are in [security](docs/security.md) and [privacy](docs/privacy.md).
