# Requirements

## Product hypothesis

A stock ARM64 Android device can run a bundled Codex app-server with a desktop-like shell rooted initially in a user-selected shared-storage workspace, while Android supplies only the capabilities the shell cannot provide.

## Required outcomes

| ID | Outcome |
|---|---|
| R1 | Package, authenticate, converse, stream, cancel, and restart the pinned Codex runtime. |
| R2 | Let the user grant all-files access and choose the absolute `cwd` sent with every turn. |
| R3 | Let Codex perform ordinary file CRUD and overwrite through its shell. |
| R4 | Put `mutool`, `tesseract`, and `officecli` on Codex's `PATH` for PDF, OCR, and Office work. |
| R5 | Let users choose Never, Auto review, Ask me, or Strict approval; default to Never. |
| R6 | Let users choose model, reasoning level, and supported speed tier. |
| R7 | Render standard Markdown safely, including code, headings, lists, tables, and HTTPS links. |
| R8 | Keep the composer visible with the keyboard and visibly animate active thinking. |
| R9 | Offer a browserless Telegram login in Settings and put `tgcli` on Codex's `PATH`. |
| R10 | Show an operation-specific foreground notification only while work is active. |
| R11 | Meet security, privacy, accessibility, compatibility, and release gates. |

## Hard constraints

- Never log credentials, authorization codes, prompts, responses, or document contents by default.
- Keep Android SDK types out of `:core`.
- Reject malformed app-server frames and approval requests.
- Keep native tool versions and downloads pinned; teach Codex to scope expensive document extraction.
- Accept only HTTP(S) links from rendered model Markdown.
- Explain clearly that all-files access is broad and the selected workspace is a starting directory, not a sandbox.

## Not in scope

- Accessibility-service automation.
- General background scheduling or an idle persistent notification.
- Additional messaging integrations before a concrete use case.
- KMP, iOS, or runtime self-update.
