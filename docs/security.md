# Security review

## Trust model

Codex output, shell commands, provider content, marketplace data, downloaded artifacts, and protocol payloads are untrusted. Protected assets are credentials, prompts and responses, shared-storage files, provider-private state, mutation evidence, and the application signing chain. The user explicitly grants broad shared-storage access.

## Boundaries

| Boundary | Main control |
|---|---|
| Shell and shared storage | The selected workspace is the initial shell directory and the user chooses the App Server approval policy. Android still blocks protected app-private storage. |
| JSON-RPC and dynamic tools | Strict UTF-8/JSONL framing, size limits, correlation, closed schemas, verified tool-to-provider mapping, current enablement, active-call/deadline checks, and fail-closed unknown methods. |
| Authentication | Login is serialized; codes are bounded and redacted; browser handoff accepts only validated official HTTPS hosts; sign-out uses `account/logout`. |
| Provider supply chain | Add-on metadata is bounded and schema-checked. Its MCP names must equal the standard plugin declaration. Downloads use GitHub HTTPS plus a pinned hash. Android inherited-package installation enforces application ID, exact version, split identity, and signing certificate. Entry points and schema digests are reverified after restart. |
| Plugin lifecycle | App Server owns source discovery, installation, enablement, and skills. Provider records contain package continuation only. Disablement keeps code but revokes dispatch; uninstall removes code only after provider cleanup. Existing threads receive hidden availability updates. |
| Provider execution | Providers exchange project-owned typed values in-process. No provider executable, shell, argv, environment protocol, HTTP server, Binder transport, or runtime code loader exists. Provider MCP definitions are disabled on Android. |
| Mutation recovery | A unique thread/turn/call key is bound to a canonical arguments hash. Approval permits are one-use. Terminal results replay exactly; dispatched work reconciles or becomes indeterminate without resubmission. |
| Network | Framework cleartext is denied. App Server uses an authenticated loopback CONNECT proxy restricted to validated TLS hosts. Provider network behavior and credentials remain inside the signed provider and its declared notices. |
| Logs and backup | Production code has no content logging or crash SDK. Backup is disabled and app domains are excluded. |

## Explicit trade-off

`MANAGE_EXTERNAL_STORAGE` makes the ordinary App Server shell broad: the selected directory is a starting point, and absolute or parent paths may reach other shared storage allowed to the app. Provider calls receive the selected workspace as a hard authorization boundary. The UI disclosure distinguishes these authorities, and distribution must satisfy restricted-permission policy.

Optional signed provider code shares the application UID and can use permissions granted to the host. Exact-version compatibility, a matching signing certificate, explicit Android installation confirmation, bounded artifacts, declared schemas, and provider-specific release review are therefore mandatory. A plugin manifest alone grants no Android execution authority.

GitHub-installed splits require Android's user-confirmed package-installer permission. Distribution channels that prohibit general package installation cannot offer this source flow; they need channel-managed feature delivery instead of weakening the installer or signature checks.

Typed mutations under Auto review remain unavailable because pinned dynamic tools expose no equivalent automatic-review bridge.
