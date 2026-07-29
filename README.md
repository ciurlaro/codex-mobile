# Codex Mobile

Codex Mobile is a lean Android host for the pinned Codex App Server `0.145.0`. It provides ChatGPT authentication, conversations, ordinary App Server shell support, workspace selection, approvals, plugin management, typed provider dispatch, and mutation recovery.

Plugins come from standard Codex GitHub marketplaces. The host downloads, validates, and atomically refreshes each public GitHub marketplace as a bounded local snapshot before registering it with App Server. Ordinary plugins remain installable from any such source. The official Documents and Telegram Android providers are compiled into the base APK from a pinned [`ciurlaro/codex-mobile-plugins`](https://github.com/ciurlaro/codex-mobile-plugins) revision. Their marketplace metadata activates bundled code only after its provider API, host version, schema, entry point, and MCP declarations match; installation never updates the Android package.

Providers declare any user-supplied secrets they require. Codex Mobile stores each plugin's values in its own Android Keystore-backed namespace and supplies them only at runtime, so public add-on artifacts contain no configured credentials.

Disabling a plugin immediately revokes its tools while retaining private state. Uninstall completes provider cleanup, removes the App Server plugin, and deletes its activation record while the bundled code remains inert. Existing conversations remain usable; installations become visible to tools in the next new chat.

## Modules

| Module | Responsibility |
|---|---|
| `:codex-agent-runtime` | Portable agent contracts, Codex adaptation, generated protocol, transport, process host, proxy, persistence, and security policy |
| `extension-provider-api` | Published KMP provider contract; its existing Kotlin package and ABI remain unchanged |
| `:extension-host` | Android marketplace snapshots, skills, providers, secrets, mutation recovery, and tool dispatch |
| `:app` | Android bootstrap, workspace selection, Compose UI, ViewModels, and application lifecycle |
| `codex-mobile-plugins` composite | Pinned Documents and Telegram implementation artifacts bundled into the app |

Build conventions live in `build-logic`; deterministic schema generation lives in `tools/protocol-generator`.

Local builds use a sibling `codex-mobile-plugins` checkout by default, or an explicit
`-PcodexMobile.providerBuild=/absolute/codex-mobile-plugins`; CI checks out the pinned revision from `gradle.properties`.

## Verification

```sh
bash scripts/verify-structure.sh
./gradlew test \
  :codex-agent-runtime:testAndroidHostTest \
  :codex-agent-runtime:verifyProtocolSource \
  :extension-provider-api:testAndroidHostTest \
  assembleDebug assembleDebugAndroidTest lint
```

The checked-in wrapper pins Gradle 9.4.1. See [architecture](docs/architecture.md), [build performance](docs/build-performance.md), [security](docs/security.md), [privacy](docs/privacy.md), and [release operations](docs/release.md).

## Licence

Codex Mobile is distributed under `GPL-3.0-or-later`. The narrow additional permission in [`LICENSES/MLKIT-EXCEPTION.txt`](LICENSES/MLKIT-EXCEPTION.txt) applies only when the optional Documents provider links the declared Google ML Kit OCR runtime. Third-party components retain their own licences.
