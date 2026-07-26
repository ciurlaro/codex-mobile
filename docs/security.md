# Security review

## Trust model

Codex output, shell commands, provider content, marketplace data, downloaded artifacts, and protocol payloads are untrusted. Protected assets are credentials, prompts and responses, shared-storage files, provider-private state, mutation evidence, and the application signing chain. The user explicitly grants broad shared-storage access.

## Boundaries

| Boundary | Main control |
|---|---|
| Shell and shared storage | The selected workspace is the initial shell directory and the user chooses the App Server approval policy. Android still blocks protected app-private storage. |
| JSON-RPC and dynamic tools | Strict UTF-8/JSONL framing, size limits, correlation, closed schemas, verified tool-to-provider mapping, current enablement, active-call/deadline checks, and fail-closed unknown methods. |
| Authentication | Login is serialized; codes are bounded and redacted; browser handoff accepts only validated official HTTPS hosts; sign-out uses `account/logout`. |
| Provider supply chain | Ordinary plugins may use any validated public GitHub marketplace snapshot registered with App Server. Android add-ons must originate from the bounded snapshot of `ciurlaro/codex-mobile-plugins`; missing, escaped, or different Git origins fail closed. Snapshot refresh is staged, validated, and atomically activated without an external Git executable. Package URLs stay on that repository's GitHub releases, metadata is bounded and schema-checked, and MCP names must equal the standard plugin declaration. Android inherited-package installation enforces application ID, exact version, split identity, and signing certificate. Entry points and schema digests are reverified after restart. |
| Plugin lifecycle | App Server owns source discovery, installation, enablement, and skills. Provider records contain package continuation only. Disablement keeps code but revokes dispatch; uninstall removes code only after provider cleanup. Existing threads receive hidden availability updates. |
| Provider execution | Providers exchange project-owned typed values in-process. No provider executable, shell, argv, environment protocol, HTTP server, Binder transport, or runtime code loader exists. Provider MCP definitions are disabled on Android. |
| Provider secrets | Each provider declares stable secret names. Values are encrypted at rest with a plugin-specific Android Keystore key, excluded from backup, injected read-only into that provider, retained on disable, and erased after successful uninstall cleanup. Missing or unreadable values fail closed. |
| Mutation recovery | A unique thread/turn/call key is bound to a canonical arguments hash. Approval permits are one-use. Terminal results replay exactly; dispatched work reconciles or becomes indeterminate without resubmission. |
| Network | Framework cleartext is denied. App Server uses an authenticated loopback CONNECT proxy restricted to validated TLS hosts. A signed provider receives only its runtime secret namespace and owns its declared network behavior and service session. |
| Logs and backup | Production code has no content logging or crash SDK. Backup is disabled and app domains are excluded. |

## Explicit trade-off

`MANAGE_EXTERNAL_STORAGE` makes the ordinary App Server shell broad: the selected directory is a starting point, and absolute or parent paths may reach other shared storage allowed to the app. Provider calls receive the selected workspace as a hard authorization boundary. The UI disclosure distinguishes these authorities, and distribution must satisfy restricted-permission policy.

Optional signed provider code shares the application UID and can use permissions granted to the host. Exact-version compatibility, a matching signing certificate, explicit Android installation confirmation, bounded artifacts, declared schemas, and provider-specific release review are therefore mandatory. A plugin manifest alone grants no Android execution authority.

Secret namespaces prevent accidental cross-plugin configuration and lifecycle coupling; they are not isolation from malicious feature code because every installed split shares the host UID and signing trust. Independent untrusted-code isolation would require a separately signed package and process boundary.

GitHub-installed splits require Android's user-confirmed package-installer permission. Distribution channels that prohibit general package installation cannot offer this source flow; they need channel-managed feature delivery instead of weakening the installer or signature checks.

Typed mutations under Auto review require explicit one-use user approval because pinned dynamic tools expose no equivalent automatic-review bridge.

The optional Documents provider links the exact Google ML Kit OCR closure recorded in its release SBOM under Google's terms. Distribution uses the narrow GPLv3 section-7 permission in `LICENSES/MLKIT-EXCEPTION.txt`; it grants no permission for another proprietary provider or dependency.
