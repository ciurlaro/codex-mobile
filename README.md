# Codex Mobile

Codex Mobile is a lean Android host for the pinned Codex App Server `0.144.6`. It provides ChatGPT authentication, conversations, ordinary App Server shell support, workspace selection, approvals, plugin management, typed provider dispatch, and mutation recovery.

Plugins come from standard Codex GitHub marketplaces. The host downloads, validates, and atomically refreshes each public GitHub marketplace as a bounded local snapshot before registering it with App Server. Ordinary plugins remain installable from any such source. Android-executable providers for the official app come only from [`ciurlaro/codex-mobile-plugins`](https://github.com/ciurlaro/codex-mobile-plugins); the host verifies that snapshot's canonical repository before accepting its signed, host-compatible feature split. Android asks the user to approve installation, restarts the app, and verifies the split before activating the standard plugin. The base APK contains no optional provider implementation, model, JNI library, or provider-specific definition.

Providers declare any user-supplied secrets they require. Codex Mobile stores each plugin's values in its own Android Keystore-backed namespace and supplies them only at runtime, so public add-on artifacts contain no configured credentials.

Disabling a plugin immediately revokes its tools while retaining its installed split and private state. Uninstall first completes provider cleanup, removes the App Server plugin, removes the split, and verifies absence after restart. Existing conversations remain usable and receive an internal availability update.

## Modules

| Module | Responsibility |
|---|---|
| `:app:android` | Compose UI, ViewModels, composition root, and foreground lifecycle |
| `:core` | Provider-neutral application contracts |
| `app-server-client` | Published KMP App Server protocol identity and transport contract |
| `:agent:codex` | Codex product adaptation, plugin lifecycle, dynamic-tool authority, and authentication |
| `:platform:android` | App Server runtime, storage checks, signed provider lifecycle, and mutation journal |
| `provider-api` | Published KMP provider contract with host-supplied workspace and mutation capabilities |
| `:provider_documents`, `:provider_telegram` | Thin base-app feature wrappers; provider behavior comes from exact external artifacts |

The base build includes no optional providers. A coordinated local provider build uses
`-PcodexMobile.providerBuild=/absolute/codex-mobile-plugins`; the host-owned wrappers then consume the same published provider coordinates through composite substitution.

## Verification

```sh
bash scripts/verify-structure.sh
./gradlew test assembleDebug assembleDebugAndroidTest lint
```

The checked-in wrapper pins Gradle 9.4.1. See [architecture](docs/architecture.md), [security](docs/security.md), [privacy](docs/privacy.md), and [release operations](docs/release.md).

## Licence

Codex Mobile is distributed under `GPL-3.0-or-later`. The narrow additional permission in [`LICENSES/MLKIT-EXCEPTION.txt`](LICENSES/MLKIT-EXCEPTION.txt) applies only when the optional Documents provider links the declared Google ML Kit OCR runtime. Third-party components retain their own licences.
