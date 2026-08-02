# Objects and responsibilities

| Object | Responsibility | Owner / lifetime |
|---|---|---|
| `AgentClient` | Controller seam for authentication, sessions, plugins, hooks, and turns | `codex-agent-client` contract |
| `CodexAgentClient` | Map generated App Server protocol into product behavior | `codex-agent-client` implementation |
| `CodexRuntime` | Carry JSON lines and typed start/I/O/EOF/exit failures | `codex-agent-client` runtime contract |
| `AndroidCodexRuntimeFactory` | Create the verified local Android App Server runtime | `codex-agent-runtime-android` public host API |
| `CodexSessionController` | Keep one active client/session across UI visibility changes | Shared controller held by foreground service |
| `AppViewModel` | Coordinate common state, actions, persistence, and session gateway | Shared lifecycle ViewModel |
| `AppPreferencesStore` | DataStore keys, defaults, validation, corruption recovery, and serialized updates | Shared app-lifetime store |
| `AppPlatform` | Narrow workspace, permission, and erase-data boundary | Shared contract implemented by Android |
| `AndroidSessionHost` | Adapt Android service binding/binder to common callbacks | Android app lifetime |
| `WorkspaceManager` | Validate permission and persist/select the shell `cwd` | Android mechanism |
