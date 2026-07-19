# Roadmap

Only one step is active at a time. A later step may refine internals, but it must not weaken the ownership or authority boundaries.

| Step | Outcome | Status |
|---|---|---|
| [01](01-runtime-premise.md) | Bundled Codex authenticates and streams in a visible Activity | Complete |
| [02](02-read-only-authority.md) | Codex reads only a user-selected SAF scope | In progress |
| [03](03-controlled-mutation.md) | One disposable mutation requires trustworthy approval | Blocked by 02 |
| [04](04-mutation-recovery.md) | Interrupted mutations reconcile without false certainty | Blocked by 03 |
| [05](05-background-lifecycle.md) | Active work survives loss of Activity visibility | Blocked by 04 and product need |
| [06](06-mvp-readiness.md) | Android MVP meets release-quality gates | Blocked by 01–05 |

Provider expansion and KMP are separate future decisions, not hidden roadmap steps.
