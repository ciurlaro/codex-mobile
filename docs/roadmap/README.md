# Roadmap

Steps 01–06 are the historical record for the original bounded-SAF MVP. The current all-files workspace supersedes Steps 02–04's architecture; those pages remain only as experiment evidence.

| Step | Outcome | Status |
|---|---|---|
| [01](01-runtime-premise.md) | Bundled Codex authenticates and streams in a visible Activity | Complete |
| [02](02-read-only-authority.md) | Historical SAF read experiment | Superseded |
| [03](03-controlled-mutation.md) | Historical Android mutation approval experiment | Superseded |
| [04](04-mutation-recovery.md) | Historical mutation recovery experiment | Superseded |
| [05](05-background-lifecycle.md) | Active work survives loss of Activity visibility | Complete |
| [06](06-mvp-readiness.md) | Android MVP meets release-quality gates | Complete |

The current architecture uses all-files access, Codex's native shell/approval policy, and bundled commands on `PATH`; it has no SAF or dynamic document/file-tool fallback.
