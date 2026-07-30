# Security review

## Trust model

Codex output, shell commands, provider content, marketplace data, downloaded artifacts, and protocol payloads are untrusted. Protected assets are credentials, prompts and responses, shared-storage files, provider-private state, mutation evidence, and the application signing chain. The user explicitly grants broad shared-storage access.

## Boundaries

| Boundary | Main control |
|---|---|
| Shell and shared storage | The selected workspace is the initial shell directory and the user chooses the App Server approval policy. Android still blocks protected app-private storage. |
| JSON-RPC and dynamic tools | Ordered JSONL delivery with bounded backpressure, UTF-8 replacement rejection, a 32 MiB decoded-line byte limit, correlation, closed schemas, verified tool-to-provider mapping, current enablement, active-call/deadline checks, and fail-closed unknown methods. `kmp-process` 0.5.0 allocates a complete decoded line before the public callback, so bounded raw-byte enforcement and acceptance of a literal U+FFFD require an upstream raw feed. |
| Authentication | Login is serialized; codes are bounded and redacted; browser handoff accepts only validated official HTTPS hosts; sign-out uses `account/logout`. |
| Provider supply chain | Ordinary plugins may use any validated public GitHub marketplace snapshot registered with App Server. Android add-ons must originate from the bounded snapshot of `ciurlaro/codex-mobile-plugins`; missing, escaped, or different Git origins fail closed. Documents and Telegram code is built from the pinned provider revision, while activation requires its bundled descriptor, entry point, host/API range, schema digest, and MCP names to match bounded marketplace metadata. Unknown native providers require a host update and are never downloaded as APKs. |
| Plugin lifecycle | App Server owns source discovery, installation, enablement, and skills. Provider records contain activation continuation only. Disablement keeps bundled code inert and revokes dispatch; uninstall removes activation only after provider cleanup. Existing threads receive hidden availability updates. |
| Provider execution | Providers exchange project-owned typed values in-process. No provider executable, shell, argv, environment protocol, HTTP server, Binder transport, or runtime code loader exists. Provider MCP definitions are disabled on Android. |
| Provider secrets | Each provider declares stable secret names. Values are encrypted at rest with a plugin-specific Android Keystore key, excluded from backup, injected read-only into that provider, retained on disable, and erased after successful uninstall cleanup. Missing or unreadable values fail closed. |
| Mutation recovery | A unique thread/turn/call key is bound to a canonical arguments hash. Approval permits are one-use. Terminal results replay exactly; dispatched work reconciles or becomes indeterminate without resubmission. |
| Network | Framework cleartext is denied. App Server uses an authenticated loopback CONNECT proxy restricted to validated TLS hosts. An activated bundled provider receives only its runtime secret namespace and owns its declared network behavior and service session. |
| Logs and backup | Production code has no content logging or crash SDK. Runtime protocol output is piped in memory, a legacy spool is deleted but never recreated, and the SQLite log guard securely deletes rows, blocks later inserts, truncates WAL state, and vacuums free pages. Backup is disabled and app domains are excluded. |

## Explicit trade-off

`MANAGE_EXTERNAL_STORAGE` makes the ordinary App Server shell broad: the selected directory is a starting point, and absolute or parent paths may reach other shared storage allowed to the app. Provider calls receive the selected workspace as a hard authorization boundary. The UI disclosure distinguishes these authorities, and distribution must satisfy restricted-permission policy.

Bundled provider code shares the application UID and can use permissions granted to the host. A pinned reviewed source revision, exact host/API compatibility, bounded metadata, and declared schemas are therefore mandatory. A plugin manifest alone grants no Android execution authority.

Secret namespaces prevent accidental cross-plugin configuration and lifecycle coupling; they are not isolation from malicious bundled code. Independent untrusted-code isolation would require a separately signed package and process boundary.

Typed mutations under Auto review require explicit one-use user approval because pinned dynamic tools expose no equivalent automatic-review bridge.

The optional Documents provider links the exact Google ML Kit OCR closure recorded in its release SBOM under Google's terms. Distribution uses the narrow GPLv3 section-7 permission in `LICENSES/MLKIT-EXCEPTION.txt`; it grants no permission for another proprietary provider or dependency.
