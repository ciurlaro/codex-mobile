# Requirements

## Product hypothesis

A stock ARM64 Android device can run a bundled Codex app-server with a desktop-like shell rooted initially in a user-selected shared-storage workspace, while Android supplies only the capabilities the shell cannot provide.

## Required outcomes

| ID | Outcome |
|---|---|
| R1 | Package, authenticate, converse, stream, cancel, and restart the pinned Codex runtime. |
| R2 | Let the user grant all-files access and choose the absolute `cwd` sent with every turn. |
| R3 | Let Codex perform ordinary file CRUD and overwrite through its shell. |
| R4 | Provide installed-by-default Documents and Telegram plugins through pinned app-server dynamic tools, with strict bounded schemas and immediate enablement checks. |
| R5 | Let users choose Never, Auto review, Ask me, or Strict approval; default to Never. |
| R6 | Let users choose model, reasoning level, and supported speed tier. |
| R7 | Render standard Markdown safely, including code, headings, lists, tables, and HTTPS links. |
| R8 | Keep the composer visible with the keyboard and visibly animate active thinking. |
| R9 | Offer browserless Telegram login in Settings and one-shot typed Telegram reads, downloads, and sends backed privately by `tgcli`. |
| R12 | Keep native document and Telegram binaries off Codex's shell `PATH`; invoke them only through fixed absolute-path Android handlers. |
| R13 | Journal typed mutations so duplicate delivery returns the exact terminal result and no crash can resubmit a dispatched provider action. |
| R10 | Show an operation-specific foreground notification only while work is active. |
| R11 | Meet security, privacy, accessibility, compatibility, and release gates. |

## Hard constraints

- Never log credentials, authorization codes, prompts, responses, or document contents by default.
- Keep Android SDK types out of `:core`.
- Reject malformed app-server frames and approval requests.
- Keep private backend versions and downloads pinned; bound document extraction, OCR, rendering, and Telegram output.
- Restrict typed file paths to the selected shared-storage workspace and reject app-private, protected, and escaped paths.
- Preserve app-server plugin configuration as the only persisted enablement authority.
- Accept only HTTP(S) links from rendered model Markdown.
- Explain clearly that all-files access is broad and the selected workspace is a starting directory, not a sandbox.

## Not in scope

- Accessibility-service automation.
- General background scheduling or an idle persistent notification.
- Additional messaging integrations before a concrete use case.
- KMP, iOS, or runtime self-update.
