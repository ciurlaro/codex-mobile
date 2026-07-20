# Privacy and data controls

## Data inventory and retention

| Data | Location / recipient | Retention and deletion |
|---|---|---|
| ChatGPT credentials | Codex files under app-private, no-backup storage | Until **Sign out of ChatGPT** (`account/logout`) or Android/app **Erase Codex Mobile data** |
| Prompts, responses, session/thread history, and Android tool results | Process memory and app-private Codex history; prompts/tool results are sent to OpenAI | Bounded visible response is process-local; persisted history remains until app-data erasure |
| Selected document scope | Private preferences plus Android's persisted URI grant | Replaced by another selection, removed by **Revoke access**, or removed by app-data erasure |
| Document content/metadata | Read from the selected provider and returned as a bounded tool result; may therefore enter Codex/OpenAI conversation history | No separate app cache; provider file remains until the user changes it outside this app; conversation copy follows history deletion above |
| Mutation recovery record | App-private, no-backup SQLite | Unresolved records remain visible. Acknowledged resolved records are pruned after 30 days; all records disappear on app-data erasure |
| Background start/active marker | Private preferences | One-use start authorization is consumed/revoked immediately; active marker clears on stop/timeout or app-data erasure |
| Android CA bundle and empty guarded runtime-log database | App-private Codex directory | Rebuilt as needed; removed on app-data erasure |

The app has no analytics, advertising, telemetry, remote crash reporter, contact/location/camera/microphone permission, or general storage permission. Production notifications contain only generic session state and are private on the lock screen.

## Backup and disclosure

`android:allowBackup="false"`, `backup_rules.xml`, and `data_extraction_rules.xml` exclude all app domains from cloud and device-transfer backup. The in-app **Privacy details** dialog discloses private storage, OpenAI transfer, content-free logging, and the exact effect of erasure. This document is the longer release disclosure and must change with behavior.

## User controls

- **Cancel sign-in** cancels a pending device-code login.
- **Sign out of ChatGPT** removes local ChatGPT credentials and stops the owned runtime. It deliberately retains selected-folder access and conversation/recovery history so sign-out is not misrepresented as full deletion.
- **Revoke access** removes the current SAF grant and opaque scope without modifying provider files.
- **Acknowledge recovery** hides an understood mutation outcome; resolved acknowledgements become eligible for 30-day pruning.
- **Erase Codex Mobile data** is confirmed with explicit scope, then calls Android's own reset-data facility. Android erases all dynamic app-private state, private external state, runtime permissions, notifications, and URI grants. Installed code and user files owned by document providers are not erased.

Uninstalling or clearing the app through Android Settings has the same local-data effect. Account-side data held by OpenAI is governed by the user's ChatGPT account controls and OpenAI policy, not by Android app-data deletion.
