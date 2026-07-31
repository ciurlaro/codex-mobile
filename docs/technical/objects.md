# Objects and responsibilities

| Object | Responsibility | Owner / lifetime |
|---|---|---|
| `AgentClient` | Existing controller-test seam for authentication, sessions, plugins, hooks, and turns | Shared process-local contract |
| `CodexAgentClient` | Map generated App Server protocol into product behavior | Shared process-local implementation |
| `AppServerConnection` | Initialization, request IDs, framing, correlation, timeouts, and event delivery | Shared, one per client |
| `CodexRuntime` | Carry JSON lines and typed start/I/O/EOF/exit failures | Shared contract, one per App Server start |
| `AndroidCodexRuntime` | Verify and launch the local process; own streams, proxy, SQLite bootstrap, and shutdown | Android mechanism |
| `LoopbackConnectProxy` | Bind loopback and bridge authorized CONNECT requests to public TLS destinations | Android mechanism per runtime |
| `ConnectProxyPolicy` | Parse/authenticate CONNECT requests and reject forbidden authority shapes | Shared policy |
| `JsonLineFramer` | Bound raw bytes, validate UTF-8, and emit strict lines | Shared runtime policy |
| `CodexSessionController` | Keep one active client/session across UI visibility changes | Shared controller held by foreground service |
| `AppViewModel` | Coordinate common state, actions, persistence, and session gateway | Shared lifecycle ViewModel |
| `AppPreferencesStore` | DataStore keys, defaults, validation, corruption recovery, and serialized updates | Shared app-lifetime store |
| `AppPlatform` | Narrow workspace, permission, and erase-data boundary | Shared contract implemented by Android |
| `AndroidSessionHost` | Adapt Android service binding/binder to common callbacks | Android app lifetime |
| `WorkspaceManager` | Validate permission and persist/select the shell `cwd` | Android mechanism |
