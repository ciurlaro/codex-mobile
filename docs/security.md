# Security review

## Trust model

Codex output, shell commands, plugin content, URLs, runtime streams, and
protocol payloads are untrusted. Protected assets are credentials, prompts and
responses, shared-storage files, app-private state, and the signing chain.

| Boundary | Main controls |
|---|---|
| Runtime supply chain | Exact release/version/revision/schema identity plus archive and executable SHA-256 verification |
| Process | Minimal environment, validated executable and paths, drained streams, bounded shutdown, forced termination, idempotent cleanup |
| Protocol | Strict bounded UTF-8 JSON-line framing, correlation, typed generated protocol, explicit overflow/failure behavior |
| Network | Authenticated loopback CONNECT proxy, TLS port 443 only, public-address validation, bounded headers/connections/timeouts |
| Authentication | Serialized login, validated official HTTPS browser targets, bounded/redacted diagnostics, protocol logout |
| Workspace | User grant and selection, canonical Android path validation, protected app-private and `Android/data`/`Android/obb` rejection |
| Approvals | Closed decisions and visible escaping of control/format characters before display |
| Persistence | Common DataStore defaults/corruption recovery; backup-disabled app-private storage |
| SQLite logs | Secure deletion, existing-row deletion, insert-blocking trigger, WAL checkpoint/truncation, and vacuum |

`MANAGE_EXTERNAL_STORAGE` gives the ordinary App Server shell broad authority:
the selected workspace is a starting directory, not a shell sandbox. The UI
must describe that authority accurately.

The app does not download or execute provider code, expose a remote runtime,
or depend on a companion repository. Official App Server plugins retain their
own declared remote behavior; Android does not invent a second execution path.
