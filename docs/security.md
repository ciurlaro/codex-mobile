# Security review

## Trust model

Codex, shell commands, documents, Telegram content, and protocol payloads are untrusted. Protected assets are ChatGPT and Telegram credentials, prompts and responses, shared-storage files, app-private state, and the signing chain. The application deliberately grants its Codex process broad shared-storage access after the user enables Android's **All files access** setting.

## Boundary review

| Boundary | Main control |
|---|---|
| Shell and shared storage | The user selects the initial workspace and chooses Never, Auto review, Ask me, or Strict Codex approval policy. Never is the default. Android still blocks other apps' private data and protected `Android/data`/`Android/obb` locations. |
| JSON-RPC | Strict UTF-8/JSONL framing, size limits, request correlation, active-session/turn checks, and fail-closed unknown methods. |
| Authentication | Login is serialized; codes are bounded and redacted; browser handoff accepts only validated HTTPS OpenAI/ChatGPT hosts; sign-out uses `account/logout`. |
| Native CLI bundle | Every archive is version/checksum pinned. `mutool`, `tesseract`, and `officecli` run as child processes under the app UID; they can read the same shared storage and app environment as Codex, so hostile-file parsing and resource exhaustion remain release risks. Runtime self-update is disabled. |
| Android IPC | Only the launcher Activity is exported. The foreground service is non-exported and its starts require a one-use private authorization. Notification intents are immutable. |
| Telegram | `tgcli` authenticates directly with Telegram using build-supplied API credentials. Its account database and generated config are backup-excluded and private; code/password prompts are never logged. Sending is a shell side effect governed by the selected Codex approval policy. |
| Network | Framework cleartext is denied. The Codex app-server uses an authenticated loopback CONNECT proxy restricted to OpenAI/ChatGPT TLS hosts. Direct Telegram CLI traffic reaches Telegram separately. |
| Logs and backup | Production code has no content logging or crash SDK. Runtime diagnostic rows are rejected. Backup is disabled and every app domain is excluded. |
| Supply chain | Runtime, wrapper, dependencies, checksums, locks, verification metadata, license/notice, and SBOM are pinned or checked. Release signing is external. |

## Explicit trade-off

`MANAGE_EXTERNAL_STORAGE` replaces the previous narrow SAF boundary. This makes Codex behave like its desktop harness and removes duplicated file tools, approval previews, mutation journaling, and provider recovery, but it also means the selected folder is not a hard containment boundary. A shell command can use an absolute path or `..` to reach other shared-storage files allowed to the app. The Settings disclosure must remain explicit, and distribution must satisfy the target store's policy for this restricted permission.

## Residual risk

The app sends prompts and requested results to OpenAI, sends requested Telegram operations to Telegram, trusts Android's CA and storage implementations, and executes a large native runtime. The CLI parsers are not sandboxed from the app UID, OCR currently ships English data only, and `MANAGE_EXTERNAL_STORAGE` requires store-policy review. MuPDF's AGPL-3.0 distribution obligations require explicit release review. Accessibility automation and runtime self-update remain excluded.
