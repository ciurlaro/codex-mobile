# Privacy and data controls

## Data inventory

| Data | Location / recipient | Retention and deletion |
|---|---|---|
| ChatGPT credentials | Backup-excluded app-private Codex storage | Until sign-out or app-data erasure |
| Prompts, responses, history, and requested tool results | App-private Codex history and OpenAI | Until app-data or account-side deletion under the respective controls |
| Workspace selection | Android private preferences | Replaced by another selection or erased with app data |
| UI selection and pending plugin setup state | App-private common DataStore | Replaced by later choices or erased with app data |
| Shared-storage files | Android shared storage | Read or changed by App Server shell/tool work; not removed by app-data erasure |
| App Server plugin cache and transcripts | Backup-excluded/app-private storage | Refreshed during use; removed by app-data erasure |
| Session certificate bundle | Backup-excluded private runtime storage | Removed when that runtime closes |

The base app has no first-party analytics, advertising, remote crash reporter,
camera, microphone, contacts, or location permission. It requests Android
network access for the local App Server's OpenAI/plugin traffic and **All files
access**, which permits broad shared-storage access and may require
distribution-store review.

Users can change the workspace, sign out of ChatGPT, uninstall or disable
official App Server plugins, and erase all Codex Mobile app data. App-data
erasure uses Android's reset-data facility and leaves shared-storage files
untouched.

Backup is disabled and both Android backup-rule formats exclude every app
domain. Account-side OpenAI data remains governed by ChatGPT account controls
and OpenAI policy.
