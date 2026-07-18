# Test strategy

Tests are evidence gates, not a count target. The roadmap matrices cover every known requirement, trust boundary, state transition, failure class, lifecycle interruption, permission change, and relevant input class. New discoveries add cases to the current step before implementation continues.

## A step is complete only when

1. Every matrix case has automated or recorded device evidence.
2. Its ignored test suites are enabled and passing.
3. No gate case contains `TODO`, fake data that bypasses the boundary, unconditional success, or permanent quarantine.
4. Required stock-device and fault-injection runs are attached to the change.
5. The previous steps still pass.

Ignored skeleton tests are specifications only. They never count as passing evidence.

## Test layers

| Layer | Proves |
|---|---|
| JVM unit | Protocol framing, event translation, policy, state transitions |
| Android instrumentation | SAF, permissions, process mechanics, lifecycle, UI approval |
| Stock-device experiment | Runtime compatibility, authentication, process death, background limits |
| Fault injection | Partial I/O, crashes, revoked grants, provider failures, uncertain mutations |
| Inspection | APK contents, manifest, logs, secrets, dependencies, release configuration |

Use the smallest layer that crosses the real boundary. Mocking `ContentResolver` cannot finish a SAF case; a happy-path emulator cannot finish a stock ARM64 runtime case.

## Commands

```sh
bash scripts/verify-structure.sh
gradle test assembleDebug assembleDebugAndroidTest
```

Instrumentation suites run on a matching emulator and at least one stock ARM64 device when their roadmap step becomes active.
