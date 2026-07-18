# Step 06 — Android MVP readiness

**Status:** Blocked by prior required steps

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

## After this step

Evaluate additional providers or KMP only from a working Android codebase and a funded product requirement.
