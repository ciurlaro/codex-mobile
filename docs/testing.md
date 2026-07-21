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
| Android instrumentation | Storage permission, workspace selection, process mechanics, lifecycle, UI behavior |
| Stock-device experiment | Runtime compatibility, authentication, process death, background limits |
| Fault injection | Parser limits, process failures, permission changes, protocol failures |
| Inspection | APK contents, manifest, logs, secrets, dependencies, release configuration |

Use the smallest layer that crosses the real boundary. Mocking a path cannot prove all-files access or turn `cwd`; a happy-path emulator cannot finish a stock ARM64 runtime case.

## Commands

```sh
bash scripts/verify-structure.sh
./gradlew test assembleDebug assembleDebugAndroidTest lint
```

With external release signing variables configured, the release gate adds:

```sh
./gradlew assembleRelease
scripts/verify-release.sh
scripts/verify-reproducible-release.sh
```

Instrumentation suites run on ARM64 API 26 and API 37/16K emulators plus a stock ARM64 API 36 device. Assumption-gated process-death, permission, network, timeout, profile, sign-out, and deletion harnesses run separately with their documented arguments; an assumption in an ordinary sweep is never counted as evidence for that gated case.
