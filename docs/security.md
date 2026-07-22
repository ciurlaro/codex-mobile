# Security review

## Trust model

Codex, shell commands, documents, Telegram content, and protocol payloads are untrusted. Protected assets are ChatGPT and Telegram credentials, prompts and responses, shared-storage files, app-private state, and the signing chain. The application deliberately grants its Codex process broad shared-storage access after the user enables Android's **All files access** setting.

## Boundary review

| Boundary | Main control |
|---|---|
| Shell and shared storage | The user selects the initial workspace and chooses Never, Auto review, Ask me, or Strict Codex approval policy. Never is the default. Android still blocks other apps' private data and protected `Android/data`/`Android/obb` locations. |
| JSON-RPC and dynamic tools | Strict UTF-8/JSONL framing, size limits, request correlation, closed schemas, fixed tool-to-plugin mapping, current enablement, active-turn/deadline checks, and fail-closed unknown methods. |
| Authentication | Login is serialized; codes are bounded and redacted; browser handoff accepts only validated HTTPS OpenAI/ChatGPT hosts; sign-out uses `account/logout`. |
| Native backend bundle | Every archive is version/checksum pinned. Backends are absent from app-server `PATH` and run only through fixed absolute-path handlers with bounded output, timeout, and backend-specific environment. They still share the app UID, so hostile-file parsing and resource exhaustion remain release risks. Runtime self-update is disabled. |
| Android IPC | Only the launcher Activity is exported. The foreground service is non-exported and its starts require a one-use private authorization. Notification intents are immutable. |
| Telegram | `tgcli` authenticates directly using build-supplied API credentials; its store and prompts are private and unlogged. Typed sends use one process with `--retries 0`; the pinned SDK's one provider request and reused `random_id` are checked while packaging. A dispatched ambiguous send is journaled indeterminate and never resubmitted automatically. |
| Documents | Typed paths must remain under the session workspace in shared storage. Reads use bounded immutable snapshots. Existing-file edits require `overwrite=true` and the current SHA-256, validate a staged sibling, and require atomic replacement. |
| Mutation recovery | A unique thread/turn/call key is bound to a canonical argument hash. Approval permits are one-use. Terminal results replay exactly; `DISPATCHED` is reconciled or made indeterminate without another provider submission. |
| Network | Framework cleartext is denied. The Codex app-server uses an authenticated loopback CONNECT proxy restricted to OpenAI/ChatGPT TLS hosts. Direct Telegram CLI traffic reaches Telegram separately. |
| Logs and backup | Production code has no content logging or crash SDK. Runtime diagnostic rows are rejected. Backup is disabled and every app domain is excluded. |
| Supply chain | Runtime, wrapper, dependencies, checksums, locks, verification metadata, license/notice, and SBOM are pinned or checked. Release signing is external. |

## Explicit trade-off

`MANAGE_EXTERNAL_STORAGE` still makes the ordinary Codex shell broad: its selected folder is a starting directory, and a shell command can use an absolute path or `..` to reach other shared storage allowed to the app. Built-in plugin handlers do enforce the selected workspace as a hard path boundary. The Settings disclosure must distinguish those two authorities, and distribution must satisfy the target store's restricted-permission policy.

## Residual risk

The app sends prompts and requested results to OpenAI, sends requested Telegram operations to Telegram, trusts Android's CA and storage implementations, and executes a large native runtime. Backends are not sandboxed from the app UID, OCR is bounded and English-only, and `MANAGE_EXTERNAL_STORAGE` requires store-policy review. Typed mutations under Auto review remain unavailable because pinned dynamic tools expose no equivalent Guardian bridge. MuPDF's AGPL-3.0 distribution obligations require explicit release review. Accessibility automation and runtime self-update remain excluded.
