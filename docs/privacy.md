# Privacy and data controls

## Data inventory

| Data | Location / recipient | Retention and deletion |
|---|---|---|
| ChatGPT credentials | Backup-excluded app-private Codex storage | Until sign-out or app-data erasure |
| Prompts, responses, history, and requested tool results | App-private Codex history and OpenAI | Until app-data/account-side deletion under the respective controls |
| Workspace selection | One absolute shared-storage path in private preferences | Replaced, cleared in Settings, or erased with app data |
| Shared-storage files | Android shared storage | Read or changed by ordinary Codex shell commands, or by enabled typed plugins within the selected workspace; never removed by app-data erasure |
| Document snapshots and mutation journal | Backup-excluded app-private storage | Bounded immutable extraction snapshots plus mutation hashes/state/exact results; removed by app-data erasure |
| Private backend assets | Backup-excluded app-private storage plus packaged native libraries | Not exposed to Codex's `PATH`; replaced on app update and removed by app-data erasure |
| Telegram credentials, session, and requests | Backup-excluded app-private storage and Telegram | Retained until Disconnect Telegram or app-data erasure; Telegram retains account-side data under its controls |

The app has no first-party analytics, advertising, remote crash reporter, or camera/microphone/contact/location permission. It does request Android **All files access**, which permits broad shared-storage access and may require special distribution-store review.

## User controls

- **Clear workspace selection** forgets the starting directory without deleting files or revoking all-files permission.
- **Manage storage access** opens Android's authoritative permission screen.
- **Sign out of ChatGPT** removes local ChatGPT credentials and stops the runtime.
- **Disable Telegram plugin** immediately removes agent authority but retains the private Telegram session for later re-enable.
- **Disconnect Telegram** makes one remote logout attempt. Confirmed success removes the private local store; ambiguous completion is reported and is not retried automatically. Android app-data deletion removes local state only and does not claim remote logout.
- **Erase Codex Mobile data** calls Android's reset-data facility, removing app-private state, permissions, notifications, and preferences while leaving shared user files untouched.

Backup is disabled and both Android backup-rule formats exclude every app domain. Account-side OpenAI data remains governed by the user's ChatGPT account controls and OpenAI policy.
