# Decisions

| Decision | Why | Revisit when |
|---|---|---|
| Use one concrete `AppServerConnection` | Process, JSONL, request correlation, and restart mechanics are one transport concern; an interface or factory adds no value | A second transport is actually implemented |
| Keep process mechanics outside core | Process launch is infrastructure, not domain policy | Another runtime needs the same contract |
| Use Codex's native approval policy | Android diff approval and journals duplicated the harness and blocked normal work | A platform side effect cannot be represented safely by Codex |
| Default approval to Never; expose four Settings choices | Desktop-like autonomy is the product default while the user retains control | Upstream policy semantics change |
| Use `MANAGE_EXTERNAL_STORAGE` and turn `cwd` | Codex already provides shell CRUD, overwrite behavior, and command approval | Store policy or product scope requires a narrow grant again |
| Bundle familiar CLI tools on `PATH` | Codex already understands command-line tools; a custom dynamic-tool bridge duplicates discovery, transport, and execution | A required capability has no viable CLI surface |
| Use a small local directory picker | The permission grants storage access; the picker only chooses the starting `cwd` | Platform offers a better absolute-directory chooser |
| Use an active-only foreground service | Authentication and active work may outlive the Activity; idle work does not justify a notification | Android offers a narrower continuation primitive |
| Use `kfastov/tgcli` for Telegram | One direct command surface supports login, reading, search, downloads, and sending without an installed-app intent | Its API or maintenance posture becomes unsuitable |
| Render Markdown with a maintained Compose renderer | Hand-parsing fenced code cannot correctly support the model's full Markdown | The renderer becomes unmaintained or unsafe |
| Keep KMP disabled | Portability is not a current requirement | iOS work is funded |
| Package a pinned ARM64 app-server | It is the proven local runtime path | Codex ships an Android-native runtime |
| Use native app-data reset for full erasure | Android authoritatively clears all private locations and permissions | Android changes that contract |

The former SAF workspace, generic file tools, exact-diff approval, mutation journal/recovery, export authority, and conflict machinery were intentionally removed as duplicate infrastructure.
