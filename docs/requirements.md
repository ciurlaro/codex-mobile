# Requirements

## Product hypothesis

A stock ARM64 Android device can run a bundled Codex app-server, authenticate a ChatGPT subscriber, stream a conversation, and execute only Android-authorized tools over user-selected resources.

## Required outcomes

| ID | Outcome | Proven in |
|---|---|---|
| R1 | Package and start the required Codex runtime in a debug APK | Step 01 |
| R2 | Exchange app-server JSON-RPC over standard input/output | Step 01 |
| R3 | Complete subscription authentication without exposing credentials | Step 01 |
| R4 | Start a session, submit a prompt, stream text, cancel, and restart safely | Step 01 |
| R5 | Read only resources inside a user-selected SAF tree | Step 02 |
| R6 | Require an accurate, explicit approval before every mutation | Step 03 |
| R7 | Treat Android's observed result as the truth for every tool call | Steps 02–04 |
| R8 | Recover mutation outcomes after process death without claiming exactly-once execution | Step 04 |
| R9 | Continue active work outside the visible Activity only when the product needs it | Step 05 |
| R10 | Meet security, privacy, accessibility, compatibility, and release gates | Step 06 |

## Hard constraints

- Default-deny every device operation not registered by Android.
- Scope resource access with SAF grants; do not treat filesystem paths as universal identifiers.
- Validate tool name, arguments, scope, approval, and current permission at execution time.
- Never accept Codex's belief as proof that an Android operation completed.
- Never log tokens, authorization codes, prompt contents, or document contents by default.
- Keep Android SDK types out of `:core`.
- Fail visibly on uncertain mutations; do not retry them generically.

## Not in scope now

- General shell access to user data.
- Accessibility-service automation.
- MCP versus dynamic-tool selection before Step 02 evidence.
- A foreground service before Step 05.
- Additional providers, iOS, KMP, runtime updating, or polished UI before the Android premise passes.
