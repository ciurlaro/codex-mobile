# Decisions

| Decision | Why | Revisit when |
|---|---|---|
| Put app-server mechanics behind `CodexRuntime` | `AppServerConnection` should own protocol state without importing processes, streams, or Android; the boundary has only one Android implementation and a tiny test fake | A second real runtime appears or the event contract proves insufficient |
| Keep process mechanics outside core | Process launch is infrastructure, not domain policy | Another runtime needs the same contract |
| Preserve typed mutation authority for app-server `0.144.6` | Never dispatches directly; Ask me and Strict use one-use approval permits; Auto review has no dynamic-tool Guardian bridge and therefore fails closed | Pinned app-server exposes a demonstrably equivalent review bridge |
| Default approval to Never; expose four Settings choices | Desktop-like autonomy is the product default while the user retains control | Upstream policy semantics change |
| Use `MANAGE_EXTERNAL_STORAGE` and turn `cwd` | Codex already provides shell CRUD, overwrite behavior, and command approval | Store policy or product scope requires a narrow grant again |
| Use app-server dynamic tools for Documents and Telegram | Strict schemas let Android enforce plugin enablement, workspace containment, limits, one-shot provider dispatch, and recovery | Pinned app-server removes dynamic tools or a narrower native platform API replaces them |
| Keep native backends private and absolute-path only | Shell-visible aliases bypass typed authority and leak backend-only environment variables | A backend becomes safe and intentionally supported as a public shell command |
| Use one global built-in-tool mutex | It closes disablement and pre-dispatch races with the smallest auditable policy | Measured contention requires per-plugin locks |
| Journal built-in mutations only | Exact replay and no-resubmit recovery are needed for typed side effects, while ordinary shell/file changes already have app-server semantics | App-server provides equivalent dynamic-tool idempotency and recovery |
| Use a small local directory picker | The permission grants storage access; the picker only chooses the starting `cwd` | Platform offers a better absolute-directory chooser |
| Use an active-only foreground service | Authentication and active work may outlive the Activity; idle work does not justify a notification | Android offers a narrower continuation primitive |
| Use `kfastov/tgcli` privately for Telegram | It supports browserless login and fixed typed operations; sends are pinned to one SDK submission with retries disabled | Its provider-call structure or maintenance posture becomes unsuitable |
| Render Markdown with a maintained Compose renderer | Hand-parsing fenced code cannot correctly support the model's full Markdown | The renderer becomes unmaintained or unsafe |
| Keep KMP disabled | Portability is not a current requirement | iOS work is funded |
| Package a pinned ARM64 app-server | It is the proven local runtime path | Codex ships an Android-native runtime |
| Use native app-data reset for full erasure | Android authoritatively clears all private locations and permissions | Android changes that contract |

The journal records only closed built-in plugin mutations and never becomes a second plugin, enablement, or ordinary filesystem store.
