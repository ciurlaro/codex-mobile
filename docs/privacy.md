# Privacy and data controls

## Data inventory

| Data | Location / recipient | Retention and deletion |
|---|---|---|
| ChatGPT credentials | Backup-excluded app-private Codex storage | Until sign-out or app-data erasure |
| Prompts, responses, history, and requested tool results | App-private Codex history and OpenAI | Until app-data or account-side deletion under the respective controls |
| Workspace selection | One absolute shared-storage path in private preferences | Replaced, cleared in Settings, or erased with app data |
| Shared-storage files | Android shared storage | Read or changed by ordinary shell work or an enabled provider; never removed by app-data erasure |
| Provider mutation journal | Backup-excluded app-private storage | Exact results and reconciliation evidence remain while the provider is installed; confirmed uninstall deletes undispatched rows and retains only identifier/hash/state tombstones until app-data erasure |
| Provider split and packaged assets | Signed optional application feature | Downloaded only after explicit confirmation and removed after successful plugin uninstall |
| User-supplied provider secrets | Plugin-specific Android Keystore-backed, backup-excluded host storage | Retained on disable; erased only after successful uninstall preparation or app-data erasure |
| Provider sessions and data | Backup-excluded provider-owned storage and any declared remote service | Governed by the provider's settings and uninstall contract |

The base app has no first-party analytics, advertising, remote crash reporter, or camera, microphone, contacts, or location permission. It requests Android **All files access**, which permits broad shared-storage access and may require distribution-store review.

## User controls

- **Clear workspace selection** forgets the starting directory without deleting files or revoking all-files permission.
- **Manage storage access** opens Android's authoritative permission screen.
- **Sign out of ChatGPT** removes local ChatGPT credentials and stops the runtime.
- **Disable a plugin** immediately removes agent authority while retaining its installed provider and private state.
- **Uninstall a plugin** first asks its provider to complete required local and remote cleanup. Ambiguous cleanup remains disabled and retryable; the app does not claim success or remove code needed to retry. Confirmed cleanup removes App Server registration, provider data, and the Android split, with absence verified after restart.
- **Erase Codex Mobile data** uses Android's reset-data facility, removing app-private state, permissions, notifications, and preferences while leaving shared files untouched.

Backup is disabled and both Android backup-rule formats exclude every app domain. Account-side OpenAI data remains governed by ChatGPT account controls and OpenAI policy. Each optional provider must declare its own recipients, retention, remote-revocation semantics, libraries, models, and licences in its distribution repository.
