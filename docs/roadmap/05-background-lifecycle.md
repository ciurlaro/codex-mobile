# Step 05 — Background lifecycle

**Status:** Blocked by Step 04 and a demonstrated product need

## Question

Can an explicitly started foreground service keep active work correct when the Activity is no longer visible?

## Scope

Move only active session ownership needed for continuation into a foreground service. Keep UI, approval, and Android authority unchanged. Do not add scheduled or reboot-autostart work.

## Exit gate

- A user action starts one service with an accurate ongoing notification.
- Activity recreation and unbinding do not duplicate sessions, turns, tools, or mutations.
- Notification cancellation stops work cleanly.
- Android restrictions and permission denial produce a visible fallback.

## Test matrix

| ID | Case | Evidence |
|---|---|---|
| S05-START-01 | Explicit user action starts one service and notification in time | Device |
| S05-START-02 | Background-only, duplicate, or unauthorized starts are rejected | Device |
| S05-START-03 | Notification permission denied/revoked has an explained fallback | Device |
| S05-NOT-01 | Notification states current work, privacy-safe status, and stop action | Inspection + UI test |
| S05-NOT-02 | Stop action cancels turn, closes process, and reaches terminal UI state | Device |
| S05-BIND-01 | Bind, unbind, rebind, rotation, and process recreation preserve one owner | Device |
| S05-BIND-02 | Multiple Activities cannot create multiple app-server processes | Stress |
| S05-LIFE-01 | Home, screen off, task removal, and Activity finish behave as documented | Device |
| S05-LIFE-02 | Force-stop terminates work; relaunch reconciles durable mutations | Fault |
| S05-LIFE-03 | Low-memory service death yields truthful recovery on relaunch | Fault |
| S05-LIFE-04 | Reboot does not silently restart or execute pending work | Device |
| S05-NET-01 | Offline, captive, handover, and prolonged loss keep bounded state | Device + Fault |
| S05-APP-01 | Approval remains an explicit visible user action; service cannot self-approve | Unit + Device |
| S05-APP-02 | Approval arriving after cancellation or changed operation is rejected | Fault |
| S05-COMP-01 | Foreground-service restrictions pass on min, target, and current APIs | Device matrix |
| S05-RES-01 | Long turn has bounded memory, CPU, wake time, and notification updates | Profile |
| S05-SEC-01 | Service and receivers are not exported; intents are validated | Inspection + Unit |

## Stop condition

If visible-Activity execution satisfies the product, skip this step and keep the service disabled.
