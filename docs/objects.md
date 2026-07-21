# Objects and responsibilities

| Object | Responsibility | Lifetime / truth |
|---|---|---|
| `AgentClient` | Authenticate, create sessions, send/cancel turns, expose provider-neutral events | Process-local; provider owns conversation semantics |
| `CodexAgentClient` | Implement app-server JSON-RPC and include the selected workspace as each turn's `cwd` | Process-local |
| `AgentEvent` | Represent authentication, session, text, approvals, shell work, completion, and failure events | Transient stream |
| `ForegroundSessionController` | Own one client/session while UI visibility changes | One active service lifetime |
| `AgentApprovalPreset` | Map Never, Auto review, Ask me, and Strict to app-server policy/reviewer fields | Persisted setting; Never is default |
| `WorkspaceManager` | Check all-files access and persist/select a shared-storage starting directory | Path preference plus current Android permission |
| `RuntimeToolBundle` | Install private CLI assets and skills, expose executable aliases, and construct the app-server environment | Rebuilt atomically when the bundle version changes |
| `TelegramCliIntegration` | Drive `tgcli` status, phone-code/2FA login, and logout without a browser | One active login process; session data in no-backup storage |
