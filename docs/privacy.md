# Privacy and data controls

## Data inventory

| Data | Location / recipient | Retention and deletion |
|---|---|---|
| ChatGPT credentials | Backup-excluded app-private Codex storage | Until sign-out or app-data erasure |
| Prompts, responses, history, and requested tool results | App-private Codex history and OpenAI | Until app-data or account-side deletion under the respective controls |
| Workspace selection | One absolute shared-storage path in private preferences | Replaced, cleared in Settings, or erased with app data |
| Shared-storage files | Android shared storage | Read or changed by ordinary shell work or an enabled provider; never removed by app-data erasure |
| Provider mutation journal | Backup-excluded app-private storage | Exact results and reconciliation evidence remain while the provider is installed; confirmed uninstall deletes undispatched rows and retains only identifier/hash/state tombstones until app-data erasure |
| Provider code and packaged assets | Bundled in the signed base APK | Remains inert when its plugin is not installed; removed only with the app |
| User-supplied provider secrets | Plugin-specific Android Keystore-backed, backup-excluded host storage | Retained on disable; erased only after successful uninstall preparation or app-data erasure |
| Provider sessions and data | Backup-excluded provider-owned storage and any declared remote service | Governed by the provider's settings and uninstall contract |
| App Server diagnostic logs | Backup-excluded Codex SQLite storage | Scrubbed after the first genuine response with secure deletion, WAL truncation, compaction, and a trigger that rejects later inserts; protocol stdout is never persisted |

The base app has no first-party analytics, advertising, remote crash reporter, or camera, microphone, contacts, or location permission. It requests Android **All files access**, which permits broad shared-storage access and may require distribution-store review.

## User controls

- **Change workspace** replaces the selected starting directory without deleting files.
- **Sign out of ChatGPT** removes local ChatGPT credentials and stops the runtime.
- **Disable a plugin** immediately removes agent authority while retaining its installed provider and private state.
- **Uninstall a plugin** first asks its provider to complete required local and remote cleanup. Ambiguous cleanup remains disabled and retryable. Confirmed cleanup removes App Server registration, provider data, secrets, and activation while bundled code remains inert.
- **Erase Codex Mobile data** uses Android's reset-data facility, removing app-private state, permissions, notifications, and preferences while leaving shared files untouched.

Backup is disabled and both Android backup-rule formats exclude every app domain. Account-side OpenAI data remains governed by ChatGPT account controls and OpenAI policy. Each optional provider must declare its own recipients, retention, remote-revocation semantics, libraries, models, and licences in its distribution repository.

The log guard fails runtime I/O closed if it cannot lock, checkpoint, or compact the database. A one-way startup cleanup removes the legacy `codex-app-server.stdout` file left by an older build; current builds stream output through an in-memory pipe and never recreate it.

The Documents provider uses bundled Google ML Kit text recognition. Recognition is available offline, but Google's terms state that ML Kit may contact Google for metrics, fixes, model updates, or hardware-compatibility information. ML Kit is present in the base APK but receives no document until the plugin is installed and invoked.
