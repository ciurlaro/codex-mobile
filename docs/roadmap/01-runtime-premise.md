# Step 01 — Runtime premise

**Status:** Complete

## Question

Can a debug APK run `codex app-server` on a stock ARM64 device, authenticate a ChatGPT subscriber, and stream one conversation while the Activity is visible?

## Scope

Package the runtime, supply Android process launch, implement only the app-server messages needed for authentication and one session, and render plain streamed text. No SAF, tools, database, service, MCP decision, or polished UI.

## Exit gate

1. A debug APK contains the required runtime and installs on a stock ARM64 device.
2. The app starts and stops app-server without an orphan process.
3. Bidirectional JSON-RPC works over stdin/stdout.
4. Subscription authentication completes.
5. One session starts, accepts a prompt, streams text, and completes or cancels.
6. Failures are visible and credentials never enter logs.
7. After app restart, observed authentication and session survival are recorded.

## Test matrix

| ID | Case | Evidence |
|---|---|---|
| S01-RUN-01 | Clean debug APK installs and launches on stock ARM64 | Device |
| S01-RUN-02 | APK contains the expected ABI/runtime files and verified checksum | Inspection |
| S01-RUN-03 | First-run runtime preparation succeeds in app-private storage | Device |
| S01-RUN-04 | Missing, corrupt, or non-executable runtime fails visibly | Fault |
| S01-RUN-05 | app-server starts once and reports readiness within a bounded timeout | Device |
| S01-RUN-06 | Normal close terminates process and closes all three streams | Device |
| S01-RUN-07 | Repeated start/close leaves no orphan and permits a new start | Device |
| S01-RUN-08 | Non-zero exit, signal, and unexpected EOF become typed failures | Fault |
| S01-IO-01 | Request IDs correlate responses and concurrent notifications | Unit |
| S01-IO-02 | Partial reads, multiple frames per read, CRLF, and UTF-8 boundaries parse | Unit |
| S01-IO-03 | Malformed JSON, missing fields, unknown methods, and unknown IDs do not deadlock | Unit |
| S01-IO-04 | stderr cannot corrupt the stdout protocol stream | Device |
| S01-IO-05 | Large messages and slow consumers apply bounded backpressure | Fault |
| S01-IO-06 | Writes after exit and cancellation races return a failure once | Fault |
| S01-AUTH-01 | Fresh install completes the supported subscription flow | Device |
| S01-AUTH-02 | User cancellation, denial, and expired verification code are recoverable | Device |
| S01-AUTH-03 | Network loss before and after authorization has a bounded retry path | Fault |
| S01-AUTH-04 | Repeated authentication does not create conflicting state | Device |
| S01-AUTH-05 | Tokens, codes, headers, prompts, and responses are absent from logs | Inspection |
| S01-AUTH-06 | Force-stop/relaunch records whether authentication survives | Device |
| S01-SES-01 | Session starts and returns a non-empty opaque `SessionId` | Device |
| S01-SES-02 | One normal prompt streams ordered deltas and one terminal event | Device |
| S01-SES-03 | Empty input is rejected locally; Unicode and multiline input round-trip | Unit + Device |
| S01-SES-04 | User cancellation stops streaming and leaves the client usable | Device |
| S01-SES-05 | Activity recreation neither duplicates the prompt nor corrupts rendering | Device |
| S01-SES-06 | app-server death mid-turn yields one visible terminal failure | Fault |
| S01-SES-07 | Relaunch records whether the provider session can resume | Device |
| S01-SEC-01 | Runtime and credentials are app-private; no component is unintentionally exported | Inspection |
| S01-COMP-01 | Minimum and target API choices are verified against at least one old and one current stock device | Device |

## Result record

- **Device/API:** RMX3851 ARM64 production device on Android 16/API 36; ARM64 emulators on API 26 and API 37.
- **Runtime version/hash:** Codex 0.144.6; app-server SHA-256 `09d6a41d6189b14317ec5d556251e5195e9a4235c28867fc75ee5c1d54be02cd`.
- **Launch mechanism:** The verified app-server is packaged as an ARM64 native library and launched directly with app-private home/state directories, Android system CAs, an authenticated allowlisted loopback CONNECT proxy, and a private SQLite runtime-log rejection trigger.
- **Authentication survival:** ChatGPT device-code authentication completed while the Activity remained visible and survived force-stop/relaunch; authorization used another device because Android blocks this app's network while a same-device browser is foregrounded.
- **Session survival:** A real prompted thread resumed after app-server restart; streaming, cancellation, client reuse, and Activity recreation also passed on the physical device.
- **Measured startup latency:** Maximum observed app-server readiness was 285 ms on the physical device, 483 ms on API 26, and 259 ms on API 37.
- **Go/no-go:** Go for the visible-Activity premise; same-device browser authorization requires Step 05 foreground execution or continued use of another device.
