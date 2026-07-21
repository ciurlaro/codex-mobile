# Privacy and data controls

## Data inventory

| Data | Location / recipient | Retention and deletion |
|---|---|---|
| ChatGPT credentials | Backup-excluded app-private Codex storage | Until sign-out or app-data erasure |
| Prompts, responses, history, and requested tool results | App-private Codex history and OpenAI | Until app-data/account-side deletion under the respective controls |
| Workspace selection | One absolute shared-storage path in private preferences | Replaced, cleared in Settings, or erased with app data |
| Shared-storage files | Android shared storage | Read or changed by Codex shell commands according to the selected approval policy; never removed by app-data erasure |
| Bundled command assets | Backup-excluded app-private storage plus packaged native libraries | Replaced on app update; removed by app-data erasure |
| Telegram credentials, session, and requests | Backup-excluded app-private storage and Telegram | Retained until Disconnect Telegram or app-data erasure; Telegram retains account-side data under its controls |

The app has no first-party analytics, advertising, remote crash reporter, or camera/microphone/contact/location permission. It does request Android **All files access**, which permits broad shared-storage access and may require special distribution-store review.

## User controls

- **Clear workspace selection** forgets the starting directory without deleting files or revoking all-files permission.
- **Manage storage access** opens Android's authoritative permission screen.
- **Sign out of ChatGPT** removes local ChatGPT credentials and stops the runtime.
- **Disconnect Telegram** logs out the bundled `tgcli` session and removes its private local store without deleting the Telegram account.
- **Erase Codex Mobile data** calls Android's reset-data facility, removing app-private state, permissions, notifications, and preferences while leaving shared user files untouched.

Backup is disabled and both Android backup-rule formats exclude every app domain. Account-side OpenAI data remains governed by the user's ChatGPT account controls and OpenAI policy.
