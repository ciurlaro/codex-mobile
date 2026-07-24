# Third-party notices

Codex Mobile packages Codex App Server `0.144.6` (Apache-2.0), Kotlin and
kotlinx libraries (Apache-2.0), AndroidX (Apache-2.0), and their locked runtime
dependencies. Exact versions and hashes are recorded in `docs/sbom.cdx.json`.

The base APK contains no Documents or Telegram provider implementation, model,
or JNI library. Those optional split packages publish their own notices and
SBOM in `ciurlaro/codex-mobile-plugins`.
