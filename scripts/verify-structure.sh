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
  README.md LICENSE LICENSES/MLKIT-EXCEPTION.txt THIRD_PARTY_NOTICES.md CONTRIBUTING.md SECURITY.md
  release-signing-certificate.sha256 settings.gradle.kts gradle.properties gradle.lockfile gradle/verification-metadata.xml
  core/build.gradle.kts agent/codex/build.gradle.kts platform/android/build.gradle.kts app/android/build.gradle.kts
  app/android/provider-addon-rules.pro
  docs/requirements.md docs/architecture.md docs/objects.md docs/decisions.md docs/security.md docs/privacy.md docs/release.md docs/sbom.cdx.json
  provider-api/settings.gradle.kts provider-api/build.gradle.kts provider-api/gradle.lockfile
  provider-api/gradle/verification-metadata.xml
  app-server-client/settings.gradle.kts app-server-client/build.gradle.kts app-server-client/gradle.lockfile
  app-server-client/gradle/verification-metadata.xml app-server-client/protocol/provenance.json
  app-server-client/protocol/codex_app_server_protocol.v2.schemas.json
  app-server-client/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/AppServerProtocolIdentity.kt
  app-server-client/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/transport/CodexRuntime.kt
  runtime-host/build.gradle.kts runtime-host/android/build.gradle.kts
  runtime-host/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/host/AppServerRuntimeDistribution.kt
  runtime-host/android/src/main/kotlin/io/github/ciurlaro/codexmobile/appserver/host/android/AndroidCodexRuntime.kt
  provider-api/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/CodexMobileProvider.kt
  agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/ProviderHost.kt
  agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/ThreadProviderStateStore.kt
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderRegistry.kt
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderPackageManager.kt
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderSecretStore.kt
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/BuiltInMutationJournal.kt
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/providers/telegram/TelegramSettingsActivity.kt
  scripts/generate-sbom.py scripts/verify-release.sh scripts/release-local.sh scripts/install-device-fast.sh
  scripts/verify-provider-uninstall-device.sh
)
for path in "${required[@]}"; do test -f "$path" || { echo "missing required file: $path" >&2; exit 1; }; done

grep -qx 'codexMobile.codexVersion=0.144.6' gradle.properties
grep -qx 'codexMobile.providerRevision=a40fefe7c3a60da14e65fef05106a07e1734afdf' gradle.properties
grep -q 'includeBuild("app-server-client")' settings.gradle.kts
grep -q 'repository: ciurlaro/codex-mobile-plugins' .github/workflows/verify.yml
grep -q 'ref: a40fefe7c3a60da14e65fef05106a07e1734afdf' .github/workflows/verify.yml
grep -q 'version = "0.144.6-1"' app-server-client/build.gradle.kts
grep -q 'const val APP_SERVER_VERSION = "0.144.6"' \
  app-server-client/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/AppServerProtocolIdentity.kt
test "$(shasum -a 256 app-server-client/protocol/codex_app_server_protocol.v2.schemas.json | cut -d' ' -f1)" = \
  '007e12d25541eb0a50bc778dfcff9e6ab88b3124c9425c4e8f79391d3538bec0'
grep -q 'GNU GENERAL PUBLIC LICENSE' LICENSE
grep -q 'GNU GPL version 3 section 7' LICENSES/MLKIT-EXCEPTION.txt
grep -qx '30934b849c0aec49f66b77c37ab95f021dba5c841e2caf005b513806f7b20765' release-signing-certificate.sha256

process_owners=$(rg -l 'ProcessBuilder|java\.lang\.Process|Runtime\.getRuntime\(\)\.exec' \
  agent/codex/src/main platform/android/src/main app/android/src/main runtime-host/android/src/main \
  --glob '!**/build/**' || true)
test "$process_owners" = "runtime-host/android/src/main/kotlin/io/github/ciurlaro/codexmobile/appserver/host/android/AndroidCodexRuntime.kt" || {
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
test -z "$(find providers -path '*/build/*' -prune -o -type f -print 2>/dev/null)"
reject_matches -n -i \
  '@codex-mobile|src/main/assets/codex/plugins|private.backend' \
  app platform agent core docs gradle/verification-metadata.xml \
  --glob '!**/build/**' --glob '!**/src/test/**' --glob '!**/src/androidTest/**'
reject_matches -n 'REQUEST_INSTALL_PACKAGES|UPDATE_PACKAGES_WITHOUT_USER_ACTION|PackageInstaller' \
  app/android/src/main platform/android/src/main --glob '!**/build/**'
grep -q 'documents-android:1.0.0' platform/android/build.gradle.kts
grep -q 'telegram-android:1.0.0' platform/android/build.gradle.kts
grep -q 'enum class ProviderDelivery { BUNDLED, LEGACY_SPLIT }' \
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderRegistry.kt
grep -q 'BUNDLED_PROVIDER_FACTORIES' \
  platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderRegistry.kt
grep -q 'AndroidKeyStore' platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderSecretStore.kt
grep -q 'AES/GCM/NoPadding' platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderSecretStore.kt
grep -q 'interface CodexMobileProvider' provider-api/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/CodexMobileProvider.kt
grep -q 'ProviderMutationJournal' provider-api/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/CodexMobileProvider.kt
grep -q 'ProviderWorkspace' provider-api/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/CodexMobileProvider.kt
grep -q 'interface ProviderSecretStore' agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/ProviderHost.kt
grep -q 'CANONICAL_PROVIDER_REPOSITORY = "ciurlaro/codex-mobile-plugins"' platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderPackageManager.kt
grep -q 'record.marketplaceRepository == CANONICAL_PROVIDER_REPOSITORY' platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidProviderRegistry.kt
grep -q 'class io.github.ciurlaro.codexmobile.provider.api.\*\*' app/android/proguard-rules.pro
grep -q 'provider-addon-rules.pro' app/android/build.gradle.kts
reject_matches -n '^-dont(optimize|obfuscate)$' app/android/provider-addon-rules.pro
grep -Fqx -- '-keep class org.drinkless.tdlib.JsonClient { *; }' app/android/provider-addon-rules.pro
grep -Fqx -- '-keep class io.legere.pdfiumandroid.core.jni.** { *; }' app/android/provider-addon-rules.pro
grep -q 'AppServerClientMethods.MarketplaceAdd' agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexAgentClient.kt
grep -q 'AppServerClientMethods.ThreadInjectItems' agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexAgentClient.kt
grep -q 'AppServerClientMethods.TurnSteer' agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexAgentClient.kt
grep -q 'mcp_servers\.' agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexAgentClient.kt

reject_matches -n '^[[:space:]]*import[[:space:]]+(android|androidx)\.' core/src agent/codex/src
reject_matches -n 'decodeFromJsonElement\(method\.paramsSerializer|AppServerRequestDescriptor\(' \
  core/src agent/codex/src --glob '*.kt'
reject_matches -n '^[[:space:]]*import[[:space:]]+(android|androidx|io\.github\.ciurlaro\.codexmobile\.(agent|app|core|platform|provider))\.' \
  app-server-client/src
reject_matches -n '^[[:space:]]*import[[:space:]]+(android|androidx|io\.github\.ciurlaro\.codexmobile\.(agent|app|core|platform))\.' provider-api/src
reject_matches -n '^[[:space:]]*import[[:space:]]+io\.github\.ciurlaro\.codexmobile\.(agent|app|core|platform|provider)\.' \
  runtime-host/src runtime-host/android/src
test "$(find platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/providers -name '*.kt' -print | wc -l | tr -d ' ')" = 1
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
