# Step 04 — Mutation recovery

**Status:** Complete

## Question

Can Android persist, interrupt, and reconcile mutations without inventing certainty or generic exactly-once behavior?

## Scope

Introduce `MutationJournal`, durable Android storage, controlled termination, reconciliation, explicit `Unknown`, and tool-specific retry rules. Continue using disposable test data until every fault point is proven.

## Exit gate

- Journal state is durable before dispatch and after every observed transition.
- A kill at every boundary resolves to `Succeeded`, `Failed`, or visible `Unknown` after restart.
- Unknown mutations are never retried automatically unless that tool's reconciliation proves retry safe.
- Journal corruption, migration, and permission loss fail closed.

## Test matrix

| ID | Case | Evidence |
|---|---|---|
| S04-STATE-01 | Allowed transitions are `Prepared → Executing → Succeeded/Failed/Unknown` | Unit |
| S04-STATE-02 | Invalid, backward, duplicate, and terminal-state transitions are rejected | Unit |
| S04-DB-01 | Record is durable before platform dispatch | Fault + Inspection |
| S04-DB-02 | State update and required reconciliation data commit atomically | Unit + Fault |
| S04-DB-03 | Concurrent writers preserve call correlation and ordering | Stress |
| S04-DB-04 | Database full, locked, corrupt, or unavailable blocks new mutation | Fault |
| S04-DB-05 | Schema upgrade and downgrade behavior is explicit and data-safe | Migration test |
| S04-KILL-01 | Kill before `Prepared` produces no operation | Fault |
| S04-KILL-02 | Kill after `Prepared` but before dispatch remains safely pending | Fault |
| S04-KILL-03 | Kill during dispatch restarts as `Unknown` until reconciled | Fault |
| S04-KILL-04 | Kill after provider success but before journal success reconciles to success | Fault |
| S04-KILL-05 | Kill after provider failure but before journal failure reconciles truthfully | Fault |
| S04-REC-01 | Reconciliation compares current Android state with recorded intent | Device |
| S04-REC-02 | Missing source/destination, both present, or neither present map explicitly | Fault |
| S04-REC-03 | Revoked grant leaves a visible, non-retried `Unknown` | Fault |
| S04-REC-04 | Repeated restart and reconciliation are stable and side-effect free | Stress |
| S04-RET-01 | Retry is denied by default and enabled only by a tested tool rule | Unit |
| S04-RET-02 | Retry cannot reuse a stale approval when resolved operation changed | Unit + Device |
| S04-RET-03 | Duplicate call ID never overwrites conflicting journal intent | Unit |
| S04-UI-01 | Pending and unknown outcomes remain visible until acknowledged/resolved | UI test |
| S04-SEC-01 | Journal contains the minimum recovery data and no credentials/content | Inspection |
| S04-MAINT-01 | Retention and deletion preserve unresolved records and remove resolved history safely | Unit + Device |

## Required record

Document each tool's reconciliation predicate and whether retry is impossible, conditionally safe, or safe after a proven no-op.

## Result record

- **Journal:** App-private `SQLiteOpenHelper` storage under `noBackupFilesDir` commits a unique record ID, call correlation, tool/scope identity, plan fingerprint, bounded recovery payload, state, outcome, timestamps, and acknowledgement. `synchronous=FULL` and compare-and-set transitions make `Prepared` durable before `Executing` and provider dispatch.
- **Storage faults:** API 26, API 37, and the API 36 physical device passed 32-writer ordering plus real locked, page-limit/full, corrupt, and unavailable database faults. Version 1 data upgrades in place to version 2; future-version downgrade throws without deleting its row.
- **Process death:** Physical force-stops passed before `Prepared`, after `Prepared`, after durable `Executing`, during a blocked provider dispatch, after stock-provider success, and after a controlled provider refusal. Restart first treats possible dispatch as `Unknown`, performs observation-only reconciliation, and never calls `execute` again.
- **Recovery/UI:** Exact source unchanged resolves `Failed`; exact source absent with one requested destination resolves `Succeeded`; both, neither, a different provider-normalized name, permission loss, malformed recovery data, or unavailable observation remain visible `Unknown`. Repeated reconciliation is stable and side-effect free; acknowledgement hides but does not resolve `Unknown`.
- **Retry:** `rename_document` reports `NEVER`. Core exposes no automatic replay; even a generally retryable tool requires a fresh prepared plan and a tool rule tested to return `SAFE_AFTER_PROVEN_NO_OP`. A copied or changed plan cannot reuse approval.
- **Privacy/maintenance:** Rename recovery stores only version, parent path/ID, source ID/name, and destination name—never document contents, tool arguments, credentials, or SAF URIs. App-UID log scans found zero fixture or credential-shaped matches. Thirty-day pruning removes only acknowledged `Succeeded`/`Failed` history and preserves every unresolved row.
- **Device cleanup:** The stock SAF grant was revoked before the six empty disposable files were permanently deleted and verified absent; temporary UI dumps were removed and the normal screen-timeout policy restored.

## Tool reconciliation record

| Tool | Reconciliation predicate | Retry |
|---|---|---|
| `rename_document` | `Succeeded` only when the exact recorded source ID/name is absent and exactly one child has the requested destination name. `Failed` only when the exact source remains and no child has that destination. Every other observation is `Unknown`. | Impossible (`NEVER`); no generic or automatic replay. |
