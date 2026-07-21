# Step 03 — Controlled mutation

**Status:** Superseded historical experiment. The current architecture uses Codex's native approval policy.

## Question

Can one disposable rename or move be previewed accurately, approved once by the user, and executed by Android?

## Scope

Use only files created for this experiment in a dedicated test tree. Add no journal or automatic retry. If execution is interrupted, report uncertainty and inspect the disposable tree manually.

## Exit gate

- Android resolves the concrete source, destination, and conflict behavior before approval.
- Approval is explicit, bound to one call ID and resolved operation, and cannot be reused.
- Denial or missing approval performs no mutation.
- The returned outcome matches provider state after execution.

## Test matrix

| ID | Case | Evidence |
|---|---|---|
| S03-APP-01 | Approval shows operation, source, destination, scope, and conflict behavior | UI test + Device |
| S03-APP-02 | Untrusted provider strings cannot imitate buttons or hide operation details | UI test |
| S03-APP-03 | Deny, dismiss, back, timeout, and Activity destruction perform no mutation | Device |
| S03-APP-04 | Approval for another call, scope, arguments, or stale preview is rejected | Unit + Device |
| S03-APP-05 | One approval cannot authorize a second execution or altered request | Unit |
| S03-APP-06 | Rapid double tap produces at most one dispatch | UI test |
| S03-POL-01 | Every registered mutating tool requires approval by default | Unit |
| S03-POL-02 | Unknown/unregistered tools and cross-scope destinations remain denied | Unit + Device |
| S03-MUT-01 | Valid rename/move changes exactly the intended disposable document | Device |
| S03-MUT-02 | No-op, empty, illegal, Unicode, long, and reserved names are handled truthfully | Device |
| S03-MUT-03 | Existing destination follows the previewed conflict policy | Device |
| S03-MUT-04 | Source missing, changed, or replaced after preview prevents stale execution | Fault |
| S03-MUT-05 | Grant revoked or downgraded after approval prevents execution | Fault |
| S03-MUT-06 | Provider refusal, null result, exception, and partial move are surfaced | Fault |
| S03-MUT-07 | Success is reported only after Android re-observes the expected state | Device |
| S03-MUT-08 | Failure does not claim rollback unless the provider state proves it | Fault |
| S03-COR-01 | Duplicate provider request never triggers generic automatic replay | Unit + Device |
| S03-COR-02 | Concurrent calls to the same source serialize or fail without hidden overwrite | Fault |
| S03-COR-03 | Cancellation before dispatch prevents mutation; after dispatch reports observed state | Fault |
| S03-LIFE-01 | Process death is exercised only against disposable data and yields no false success | Fault |
| S03-SEC-01 | Approval and result logs exclude document content and sensitive identifiers | Inspection |

## Deliberate limitation

Crash consistency is not a completion criterion here. That belongs to Step 04.

## Result record

- **Operation and authority:** `rename_document` operates only inside one explicitly writable SAF tree. Android resolves the source, same-parent destination, provider rename support, and reject-on-conflict policy before approval, then revalidates them immediately before dispatch.
- **Approval:** The trusted Android dialog displays escaped operation, source, destination, scope, and conflict fields. Core binds a grant to the exact prepared plan object, consumes it once, and requires user approval for every mutating tool even if a caller policy tries to allow it automatically. Deny, dismiss, back, 30-second timeout, Activity destruction, altered plans, and rapid repeated taps do not dispatch.
- **Observed truth:** Dispatches serialize behind one process-wide mutex. Android re-observes the parent after a provider return, null, exception, partial effect, or post-dispatch cancellation; it reports success only for the expected destination with the source absent, failure only for an observed unchanged state, and otherwise `Unknown`. No retry or journal exists in this step.
- **Fault coverage:** Cross-Binder provider tests cover stale, missing, and replaced sources; revoked and downgraded grants; conflicts and edge-case names; duplicate and concurrent calls; provider refusal, null, exception, partial effects, and cancellation. The six-test suite passes on API 26, physical API 36, and API 37.
- **Physical evidence:** The stock external-storage provider passed one-shot approval, denial/dismiss/back/timeout/destruction, and observed rename checks against an empty disposable tree. A pending approval was externally force-stopped; the process crashed, the source remained unchanged after restart, and no stale approval reappeared.
- **Security and cleanup:** The restarted app log contained zero URI or fixture-identifier matches, production sources contain no direct logging calls, and the release manifest excludes the debug approval-test Activity. The SAF grant was revoked before the disposable tree was permanently removed.
- **Regression:** Structural verification, unit tests, debug/test APK assembly, lint, release assembly, API 26/API 37 instrumentation, physical approval UI, physical provider tests, and the stock-provider suite pass.
