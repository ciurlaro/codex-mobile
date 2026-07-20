# Step 06 — Android MVP readiness

**Status:** In progress — final local audit and remote CI pending

## Goal

Turn the proven experiment into a releasable Android MVP without expanding provider, platform, or automation scope.

## Exit gate

All prior matrices pass on the supported device/API matrix, release artifacts are reproducible and inspectable, user data controls work, and no critical security, privacy, accessibility, or correctness issue remains.

## Test matrix

| ID | Case | Evidence |
|---|---|---|
| S06-E2E-01 | Fresh install → auth → prompt → stream → scoped read completes | Device |
| S06-E2E-02 | Approved disposable mutation completes and is reported truthfully | Device |
| S06-E2E-03 | Denial, cancellation, revoked grant, offline, and process death recover | Device + Fault |
| S06-E2E-04 | Upgrade preserves only compatible credentials, scopes, sessions, and journal | Upgrade test |
| S06-SEC-01 | Threat review covers provider input, JSON-RPC, SAF, IPC, logs, backup, and supply chain | Review |
| S06-SEC-02 | No arbitrary shell/user-data path, exported authority, or implicit mutation exists | Inspection |
| S06-SEC-03 | Fuzzed protocol/tool inputs fail closed within memory/time bounds | Fuzz |
| S06-PRIV-01 | Data inventory, retention, backup, deletion, and privacy disclosure match behavior | Inspection |
| S06-PRIV-02 | Sign-out and clear-data remove app credentials/history without touching user files | Device |
| S06-PRIV-03 | Release logs and crash reports contain no secrets or document/prompt content | Inspection |
| S06-A11Y-01 | TalkBack order, labels, roles, approval wording, and error announcements work | Device |
| S06-A11Y-02 | Font scaling, contrast, touch targets, switch access, and reduced motion work | Device |
| S06-UX-01 | Loading, empty, cancellation, retry, unknown mutation, and fatal states are actionable | UI test |
| S06-PERF-01 | Startup, first token, long stream, large listing, and memory meet recorded budgets | Profile |
| S06-PERF-02 | No leaked process, descriptor, stream, grant, coroutine, Activity, or service | Stress + Profile |
| S06-COMP-01 | Supported API/device/provider matrix passes in debug and release builds | Device matrix |
| S06-REL-01 | Release signing, shrinking, manifest, network security, and backup configuration are verified | Inspection |
| S06-REL-02 | Runtime provenance, checksum, license, dependency lock, and SBOM are recorded | Inspection |
| S06-REL-03 | Reproducible release build installs and authenticates on a clean stock device | Device |
| S06-OPS-01 | Crash, auth failure, protocol mismatch, and provider failure are diagnosable without sensitive logs | Drill |
| S06-REG-01 | Every prior gate runs; ignored/TODO gate tests are zero | CI |

## Result record (pending final CI)

- **End to end and upgrade:** The stock API 36 device passed authenticated prompt/stream/cancel/recreate, live dynamic list/read, one approved disposable rename, denial paths, and all prior recovery/lifecycle records. An in-place version-code 1→2 install preserved existing credential, SAF scope, five history records, and journal metadata without reading their contents. A clean signed release completed same-device user-controlled authentication through the official browser and rendered a non-empty response to an opaque test turn.
- **Security and privacy:** `docs/security.md` covers provider input, JSON-RPC, browser/authentication, SAF, mutation, IPC, network, logs, backup/deletion, and supply chain. Deterministic protocol fuzzing exercises 2,048 frames and Android tool fuzzing exercises 512 argument sets within bounded time. Source/APK inspection finds no shell or universal path tool, implicit mutation, exported authority, production logger, analytics, or crash SDK. Release UI sign-out completed `account/logout`, stopped the service/runtime, retained the unrelated disposable SAF grant, and required fresh authorization after restart. Confirmed native app-data erasure then removed the app process, service, runtime, notification, notification permission, and persisted tree grant while keeping the signed APK and all four provider files.
- **Accessibility and UX:** API 26, stock API 36, and API 37/16K pass heading/order/role/live-region checks, explicit approval and erasure wording, 200% font scale, reduced animation, named 48dp touch targets, and scroll/switch actions. Long real recovery state exposed and fixed clipped-target and older UI-test scrolling assumptions. Loading, retry, cancellation, unknown recovery, fatal diagnostics, privacy, and destructive confirmations remain actionable without content logs.
- **Performance and compatibility:** Stock persisted-account readiness remained under 442 ms for the app-server process in the final debug sweep; prior live first-token, long-turn, 2,048-entry listing, and two-minute profiles remain within the recorded budgets. A warmed 20-recreation measurement after a 20-recreation framework warm-up retains at most eight FDs/threads and stays below 192 MiB PSS. The complete app package returned `OK (38 tests)` on API 26 and API 37/16K, the prior 36-method package passed on stock API 36, and both newly added destructive sign-out methods passed separately on stock. Platform packages returned `OK (28 tests)` on all three devices; assumption-gated cases are recorded separately and are not treated as ordinary-sweep proof.
- **Release and provenance:** Version 0.1.0/code 2 is externally signed, R8/resource-shrunk, framework-cleartext-disabled, backup-excluded, and contains one ARM64 runtime with the recorded checksum and upstream license/notice. The final 246-task Gradle gate passed 38 JVM tests, debug/test packaging, lint, and signed release assembly. Gradle 9.4.1 wrapper checksums, strict dependency verification, lock files, and CycloneDX 1.6 SBOM are checked in. Two clean cache-disabled builds again produced byte-identical APKs with SHA-256 `316f5958b9a199eb13854efe72cafc1cdfa8b9cb4e155767815411380be8c545`; the release verifier passes and the clean stock install retained all four disposable provider files.
- **Pending before completion:** Commit the reviewed Step 06 change set and require green remote CI.

## After this step

Evaluate additional providers or KMP only from a working Android codebase and a funded product requirement.
