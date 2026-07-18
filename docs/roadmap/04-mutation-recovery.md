# Step 04 — Mutation recovery

**Status:** Blocked by Step 03

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
