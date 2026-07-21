---
name: tgcli
description: Use Telegram from the Android shell to read, search, sync, send, or download messages and files.
---

# tgcli

Use the bundled `tgcli` command when the user asks about Telegram chats,
messages, contacts, groups, channels, or files. The Settings integration owns
login; if a command reports that authentication is missing, ask the user to
connect Telegram in Codex Mobile Settings.

Prefer `--json` for machine-readable output.

```bash
tgcli channels list --limit 20 --json
tgcli messages list --chat @username --limit 50 --json
tgcli messages search "query" --chat @channel --source live --json
tgcli contacts search "alex" --json
tgcli send text --to @username --message "Hello" --json
tgcli send file --to @username --file ./report.pdf --caption "FYI" --json
tgcli media download --chat @channel --id 12345 --json
```

Use `tgcli [command] --help` for the complete upstream command surface. Sending
is a real external side effect, so follow the active Codex approval policy.
