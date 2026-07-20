# Step 05 — Background lifecycle

**Status:** Complete

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

The stop condition does not apply. Step 01 showed that opening the same-device browser backgrounds the Activity and blocks its network, while the product requires that authentication and an active turn continue when the Activity is not visible.

## Result record

- **Ownership and start:** One explicit UI action creates one non-exported `dataSync` foreground service, one controller, and one app-server client. A private one-use authorization rejects duplicate, background-only, malformed, and unsolicited starts. The service is `START_NOT_STICKY`; no boot receiver, alarm, job, or scheduled restart exists.
- **Notification and denial:** The low-importance ongoing notification contains only generic private state plus immutable Open and Stop actions. API 37 denial/revocation testing kept the service in Android's Active apps surface and showed an in-app explanation. The stock API 36 device also passed the real permission dialog path after its OEM ROM rejected shell permission changes. Stop cancelled bounded work, closed the runtime, cleared the durable active marker, and removed the notification.
- **Binding and approval:** API 26, API 37, and the stock API 36 device passed bind/unbind/rebind, Activity recreation, concurrent bind clients, duplicate submission stress, and one controller identity. Tool requests have one UI claim; mutation approval remains an explicit visible one-use action and a late, detached, cancelled, or changed request cannot dispatch.
- **Lifecycle faults:** Home, screen off/on, Activity finish, and task removal retained the same owner on API 26 and API 37. API 37 external force-stop, app-UID `SIGKILL`, and full reboot removed work without restart; a fresh process detected the durable stale marker, reconciled mutation state, reported the interruption, and cleared the marker. A system-injected API 35+ `dataSync` timeout invoked `onTimeout`, closed work, and removed foreground state.
- **Network and browser:** A real API 37 Wi-Fi loss/restore cycle remained in bounded retry, authorization-pending, or ready state and recovered after successful authentication cancellation; this exposed and fixed a stale authentication guard. Same-device Firefox authorization completed on the stock device while the service remained foreground, then persisted across app-server restarts. Captive-style recoverable errors use the same bounded failure path at the controller seam.
- **Resource and privacy profile:** Two-minute live app-server background windows kept one production app process, one native runtime, and one notification. API 37 app PSS was 106.1 MiB then 105.9 MiB and runtime PSS was 58.4 MiB then 58.8 MiB; the stock device sampled 103.3 MiB app PSS and 0% CPU. Neither device showed an app-owned wake lock. A stock-device count-only scan found 426 app-related log lines, zero sensitive-pattern matches, and zero crash-buffer mentions. Authentication events redact their URL and code when stringified. Streamed text is capped at 256 KiB and notification writes are state-deduplicated.
- **Compatibility and regression:** Five ordinary Step 05 cases pass on API 26, stock API 36, and API 37. All seven explicitly gated denial/browser/network/timeout/external-fault/profile harnesses were run separately and pass. The final stock build also passed persisted-account streaming, cancellation, backgrounding, and Activity recreation. The full app sweep passes 51 tests on both emulators, the platform sweep passes 32, the complete JVM/build/lint/release regression passes 241 Gradle tasks, and structural verification passes.
- **Go/no-go:** Go. Explicit foreground execution preserves one truthful owner outside Activity visibility without moving approval or Android authority into the service.
