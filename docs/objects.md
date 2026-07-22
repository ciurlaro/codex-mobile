# Objects and responsibilities

| Object | Responsibility | Lifetime / truth |
|---|---|---|
| `AgentClient` | Authenticate, create sessions, send/cancel turns, expose provider-neutral events | Process-local; provider owns conversation semantics |
| `CodexRuntime` | Carry JSON lines and typed start/I/O/EOF/exit failures across the one narrow runtime boundary | One instance per app-server start |
| `AndroidCodexRuntime` | Own app-server process launch, minimal environment, proxy, streams, log guard, exit watch, and shutdown | Android-private runtime implementation |
| `AppServerConnection` | Own initialization, JSON-RPC IDs, framing semantics, request correlation, writes, timeouts, and restart cleanup | One per Codex client; no process or Android imports |
| `CodexAgentClient` | Map authentication, conversations, turns, approvals, plugins, dynamic-tool authority, and shell activity to core events | Process-local |
| `AgentEvent` | Represent authentication, session, text, approvals, shell work, completion, and failure events | Transient stream |
| `ForegroundSessionController` | Own one client/session while UI visibility changes | One active service lifetime |
| `AgentApprovalPreset` | Map Never, Auto review, Ask me, and Strict to app-server policy/reviewer fields | Persisted setting; Never is default |
| `WorkspaceManager` | Check all-files access and persist/select a shared-storage starting directory | Path preference plus current Android permission |
| `BuiltInPluginBundle` | Seed the app-owned marketplace and built-in plugin bundles | App-owned plugin files; app-server config remains authoritative |
| `PrivateBackendBundle` | Install private backend assets and start fixed backends with minimal environments | Rebuilt atomically when the bundle version changes; absent from the app-server `PATH` |
| `AndroidBuiltInToolDispatcher` | Validate typed paths/arguments, create snapshots, and run Documents or Telegram operations | One process-local dispatcher behind the global gate |
| `BuiltInMutationJournal` | Bind built-in mutation IDs to argument hashes, state, hashes, and exact results | Backup-excluded SQLite; built-in mutations only |
| `TelegramIntegration` | Drive Telegram status, phone-code/2FA login, and logout without a browser | One active login process; session data in no-backup storage |
