# Codex Mobile

Codex Mobile is a lean Android host for the pinned Codex App Server `0.144.6`. It provides ChatGPT authentication, conversations, ordinary App Server shell support, workspace selection, approvals, plugin management, typed provider dispatch, and mutation recovery.

Plugins come from standard Codex GitHub marketplaces. A plugin may also publish a signed, host-compatible Android feature split that implements its dynamic tools through the project-owned provider contract. Android asks the user to approve installation, restarts the app, and the host verifies the split before activating the standard plugin. The base APK contains no optional provider implementation, model, JNI library, or provider-specific definition.

Providers declare any user-supplied secrets they require. Codex Mobile stores each plugin's values in its own Android Keystore-backed namespace and supplies them only at runtime, so public add-on artifacts contain no configured credentials.

Disabling a plugin immediately revokes its tools while retaining its installed split and private state. Uninstall first completes provider cleanup, removes the App Server plugin, removes the split, and verifies absence after restart. Existing conversations remain usable and receive an internal availability update.

## Modules

| Module | Responsibility |
|---|---|
| `:app:android` | Compose UI, ViewModels, composition root, and foreground lifecycle |
| `:core` | Provider-neutral application contracts |
| `:agent:codex` | App Server protocol, plugin lifecycle, dynamic-tool authority, and authentication |
| `:platform:android` | App Server runtime, storage checks, signed provider lifecycle, and mutation journal |

Optional provider feature projects are supplied explicitly at build time with `-PcodexMobile.providerProjects=/absolute/project|...`. A normal host build includes none.

## Verification

```sh
bash scripts/verify-structure.sh
./gradlew test assembleDebug assembleDebugAndroidTest lint
```

The checked-in wrapper pins Gradle 9.4.1. See [architecture](docs/architecture.md), [security](docs/security.md), [privacy](docs/privacy.md), and [release operations](docs/release.md).
