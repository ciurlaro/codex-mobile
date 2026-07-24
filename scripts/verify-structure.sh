#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

reject_matches() {
  if rg "$@"; then
    echo "forbidden repository content found" >&2
    exit 1
  fi
}

required=(
  README.md settings.gradle.kts gradle.properties gradle.lockfile gradle/verification-metadata.xml
  core/build.gradle.kts agent/codex/build.gradle.kts platform/android/build.gradle.kts app/android/build.gradle.kts
  docs/requirements.md docs/architecture.md docs/objects.md docs/decisions.md docs/security.md docs/privacy.md docs/release.md docs/sbom.cdx.json
  agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexRuntime.kt
  agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexMobileProvider.kt
  agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/ThreadProviderStateStore.kt
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidCodexRuntime.kt
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderRegistry.kt
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderPackageManager.kt
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderSecretStore.kt
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/BuiltInMutationJournal.kt
  scripts/generate-sbom.py scripts/verify-release.sh scripts/release-local.sh
)
for path in "${required[@]}"; do test -f "$path" || { echo "missing required file: $path" >&2; exit 1; }; done

grep -qx 'codexMobile.codexVersion=0.144.6' gradle.properties

process_owners=$(rg -l 'ProcessBuilder|java\.lang\.Process|Runtime\.getRuntime\(\)\.exec' \
  agent/codex/src/main platform/android/src/main app/android/src/main --glob '!**/build/**' || true)
test "$process_owners" = "platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidCodexRuntime.kt" || {
  echo "AndroidCodexRuntime must be the only host child-process owner" >&2
  printf '%s\n' "$process_owners" >&2
  exit 1
}

if find app/android/src/main platform/android/src/main -path '*/build/*' -prune -o -type f \
  \( -name '*.js' -o -name '*.mjs' -o -name '*.cjs' -o -name '*.zip' -o -name '*.so' \) \
  ! -name libcodex_app_server.so -print | grep -q .; then
  echo "unexpected runtime payload in the host" >&2
  exit 1
fi

test ! -d platform/android/src/main/assets/codex/plugins
if find app platform agent core -path '*/build/*' -prune -o -type d -name providers -print | grep -q .; then
  echo "provider implementation directory found in the host" >&2
  exit 1
fi
reject_matches -n -i \
  '@codex-mobile|src/main/assets/codex/plugins|private.backend' \
  app platform agent core docs gradle/verification-metadata.xml --glob '!**/build/**'
grep -q 'REQUEST_INSTALL_PACKAGES' app/android/src/main/AndroidManifest.xml
grep -q 'MODE_INHERIT_EXISTING' platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderPackageManager.kt
grep -q 'removeSplit' platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderPackageManager.kt
grep -q 'const val PROVIDER_API = 2' platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderPackageManager.kt
grep -q 'AndroidKeyStore' platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderSecretStore.kt
grep -q 'AES/GCM/NoPadding' platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderSecretStore.kt
grep -q 'marketplace/add' agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexAgentClient.kt
grep -q 'thread/inject_items' agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexAgentClient.kt
grep -q 'turn/steer' agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexAgentClient.kt
grep -q 'mcp_servers\.' agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexAgentClient.kt

reject_matches -n '^[[:space:]]*import[[:space:]]+(android|androidx)\.' core/src agent/codex/src
reject_matches -n '@Ignore|TODO[[:space:]]*\(' --glob '*.kt' core/src agent/codex/src platform/android/src app/android/src
reject_matches -n '(^|[^[:alnum:]_])(Log\.[vdiwe]|println|print|System\.(out|err))[[:space:]]*\(' \
  --glob '*.kt' core/src/main agent/codex/src/main platform/android/src/main app/android/src/main --glob '!**/build/**'

wrapper_hash=$(openssl dgst -sha256 -r gradle/wrapper/gradle-wrapper.jar | cut -d' ' -f1)
test "$wrapper_hash" = '55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c'
grep -q '^distributionUrl=.*gradle-9\.4\.1-bin\.zip$' gradle/wrapper/gradle-wrapper.properties
grep -q '^distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb$' gradle/wrapper/gradle-wrapper.properties
grep -q 'android:allowBackup="false"' app/android/src/main/AndroidManifest.xml
grep -q '<base-config cleartextTrafficPermitted="false"' app/android/src/main/res/xml/network_security_config.xml
scripts/generate-sbom.py --check

echo 'structure verified'
