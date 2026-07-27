# Objects and responsibilities

| Object | Responsibility | Truth / lifetime |
|---|---|---|
| `AgentClient` | Authenticate, manage plugins, create sessions, and send or cancel turns | Process-local facade |
| `CodexRuntime` | Carry JSON lines and typed runtime failures | One instance per App Server start |
| `AndroidCodexRuntime` | Own App Server launch, environment, streams, exit, and shutdown | Sole Android child-process owner |
| `AppServerConnection` | Own initialization, request IDs, framing, correlation, and timeouts | One per client; no Android/process imports |
| `CodexAgentClient` | Map App Server authentication, plugins, turns, approvals, dynamic tools, and availability updates | Process-local |
| `PluginProviderHost` | Coordinate optional provider installation/removal with the standard plugin lifecycle | Project-owned host contract |
| `AndroidProviderPackageManager` | Validate marketplace metadata and activate or remove bundled providers around standard plugin lifecycle | Android provider authority |
| `AndroidProviderRegistry` | Load only recorded, metadata-matched bundled providers and migrate legacy split records | Backup-excluded provider lifecycle state; never enablement |
| `AndroidProviderSecretStore` | Encrypt and scope user-supplied configuration secrets for one installed plugin | Plugin-specific Android Keystore key; retained on disable and cleared after prepared removal |
| `CodexMobileProvider` | Declare stable tools, execute typed calls, expose settings, and prepare removal | One instance per activated bundled provider per process |
| `ProviderToolDispatcher` | Map closed tool identifiers to verified providers | Process-local derived state |
| `ThreadProviderStateStore` | Preserve each thread's original provider schemas and last announced availability | Backup-excluded per-thread state |
| `WorkspaceManager` | Validate all-files access and persist/select the shell `cwd` | Android permission plus selected path |
| `BuiltInMutationJournal` | Bind mutation IDs to argument hashes, state, pre/post evidence, and exact results; compact removed providers to replay-prevention tombstones | Backup-excluded SQLite |
| `CodexSessionController` | Keep one active client/session across UI visibility changes | Active foreground-service lifetime |
