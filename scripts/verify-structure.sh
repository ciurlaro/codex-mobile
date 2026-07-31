#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

fail() {
  echo "structure verification failed: $*" >&2
  exit 1
}

reject_matches() {
  if rg "$@"; then
    fail "forbidden repository content found"
  fi
}

required=(
  README.md LICENSE THIRD_PARTY_NOTICES.md CONTRIBUTING.md SECURITY.md
  release-signing-certificate.sha256 settings.gradle.kts gradle.properties
  gradle/verification-metadata.xml
  docs/requirements.md docs/architecture.md docs/objects.md docs/decisions.md
  docs/security.md docs/privacy.md docs/release.md docs/sbom.cdx.json
  settings-gradle.lockfile
  modules/android/app/gradle.lockfile
  modules/multiplatform/codex-shared/gradle.lockfile
  modules/tooling/protocol-generator/gradle.lockfile
  modules/tooling/build-logic/gradle.lockfile
  modules/tooling/build-logic/settings-gradle.lockfile
  modules/android/app/build.gradle.kts
  modules/android/app/src/main/AndroidManifest.xml
  modules/android/app/src/main/kotlin/io/github/ciurlaro/codexmobile/app/runtime/bootstrap/AndroidCodexRuntime.kt
  modules/multiplatform/codex-shared/build.gradle.kts
  modules/multiplatform/codex-shared/protocol/schema/provenance.json
  modules/multiplatform/codex-shared/protocol/schema/codex_app_server_protocol.v2.schemas.json
  modules/multiplatform/codex-shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/AppServerProtocolIdentity.kt
  modules/multiplatform/codex-shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/agent/AgentClient.kt
  modules/multiplatform/codex-shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/agent/CodexAgentClient.kt
  modules/tooling/build-logic/build.gradle.kts
  modules/tooling/build-logic/settings.gradle.kts
  modules/tooling/protocol-generator/build.gradle.kts
  scripts/generate-sbom.py scripts/verify-release.sh
  scripts/verify-reproducible-release.sh scripts/verify-source-size.sh
  scripts/run-android-device-smoke.sh
)
for path in "${required[@]}"; do
  [[ -f $path ]] || fail "missing required file: $path"
done
for script in \
  scripts/generate-sbom.py \
  scripts/prepare-codex-runtime.sh \
  scripts/run-android-device-smoke.sh \
  scripts/verify-release.sh \
  scripts/verify-reproducible-release.sh \
  scripts/verify-source-size.sh \
  scripts/verify-structure.sh; do
  [[ -x $script ]] || fail "required script is not executable: $script"
done

expected_modules=$(
  printf '%s\n' \
    modules/android/app/build.gradle.kts \
    modules/multiplatform/codex-shared/build.gradle.kts \
    modules/tooling/build-logic/build.gradle.kts \
    modules/tooling/protocol-generator/build.gradle.kts
)
actual_modules=$(find modules -name build.gradle.kts -type f | sort)
[[ $actual_modules == "$expected_modules" ]] || {
  printf 'expected module roots:\n%s\nactual module roots:\n%s\n' \
    "$expected_modules" "$actual_modules" >&2
  fail "unexpected Gradle module root"
}

legacy_roots=(
  src core agent app app-server-client platform provider-api runtime-host
  build-logic tools providers
)
for path in "${legacy_roots[@]}"; do
  [[ ! -e $path ]] || fail "legacy root returned: $path"
done

[[ ! -d modules/multiplatform/codex-shared/src/androidMain ]] ||
  fail "shared production must be physically commonMain-only"
[[ -z $(find modules -type d \( -name build -o -name .gradle -o -name .kotlin \) -print -quit) ]] ||
  fail "module-local build/cache directory found"
[[ -z $(find modules/android/app/src/main -type d -name jniLibs -print -quit) ]] ||
  fail "native runtime must be generated under centralized build output"

while IFS= read -r source; do
  case "$source" in
    modules/multiplatform/codex-shared/src/commonMain/kotlin/* | \
    modules/multiplatform/codex-shared/src/commonTest/kotlin/* | \
    modules/android/app/src/main/kotlin/* | \
    modules/android/app/src/test/kotlin/* | \
    modules/android/app/src/androidTest/kotlin/* | \
    modules/tooling/build-logic/src/main/kotlin/* | \
    modules/tooling/build-logic/src/test/kotlin/* | \
    modules/tooling/protocol-generator/src/main/kotlin/* | \
    modules/tooling/protocol-generator/src/test/kotlin/*) ;;
    *) fail "Kotlin source outside agreed scaffolding: $source" ;;
  esac
done < <(find modules -type f -name '*.kt' | sort)

reject_matches -n '^[[:space:]]*import[[:space:]]+(android\.|java\.)' \
  modules/multiplatform/codex-shared/src/commonMain --glob '*.kt'

process_owners=$(rg -l 'ProcessBuilder\(|java\.lang\.Process' \
  modules --glob '*.kt' | sort || true)
expected_process_owner=modules/android/app/src/main/kotlin/io/github/ciurlaro/codexmobile/app/runtime/bootstrap/AndroidCodexRuntime.kt
[[ $process_owners == "$expected_process_owner" ]] || {
  printf 'child-process owners:\n%s\n' "$process_owners" >&2
  fail "AndroidCodexRuntime must be the sole child-process owner"
}

reject_matches -n \
  'codex-mobile-plugins|provider-api|extension-provider-api|agent/codex|app-server-client|runtime-host|platform/android|app/android|src/modules|:app:android|kmp-process|kmp-file|kotlinx-io|ktor-network|codexMobile\.provider' \
  README.md CONTRIBUTING.md SECURITY.md THIRD_PARTY_NOTICES.md docs scripts .github \
  .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle/libs.versions.toml modules \
  --glob '!verify-structure.sh' \
  --glob '!**/protocol/schema/**' --glob '!**/protocol/generated/**'

reject_matches -n \
  'maven\.pkg\.github\.com|oss\.sonatype\.org/content/repositories/snapshots|mavenLocal\(\)|SNAPSHOT' \
  settings.gradle.kts modules gradle/libs.versions.toml --glob '*.kts' --glob '*.toml'

reject_matches -n \
  'REQUEST_INSTALL_PACKAGES|UPDATE_PACKAGES_WITHOUT_USER_ACTION|PackageInstaller' \
  modules/android/app/src/main
reject_matches -n '@Ignore|TODO[[:space:]]*\(' modules --glob '*.kt'
reject_matches -n \
  '(^|[^[:alnum:]_])(Log\.[vdiwe]|println|print|System\.(out|err))[[:space:]]*\(' \
  modules/multiplatform/codex-shared/src/commonMain \
  modules/android/app/src/main --glob '*.kt'

if find modules/android/app/src/main -type f \
  \( -name '*.js' -o -name '*.mjs' -o -name '*.cjs' -o -name '*.zip' -o -name '*.so' \) \
  -print | grep -q .; then
  fail "unexpected source-tree runtime payload"
fi

grep -qx 'codexMobile.codexVersion=0.145.0' gradle.properties
grep -q 'const val APP_SERVER_VERSION = "0.145.0"' \
  modules/multiplatform/codex-shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/AppServerProtocolIdentity.kt
schema_hash=$(openssl dgst -sha256 -r \
  modules/multiplatform/codex-shared/protocol/schema/codex_app_server_protocol.v2.schemas.json |
  cut -d' ' -f1)
[[ $schema_hash == 32b26f2ab3fb7a4a409db958f438f48b0ef106e3a01468f8618fdf65bc823cc4 ]] ||
  fail "pinned protocol schema hash changed"

wrapper_hash=$(openssl dgst -sha256 -r gradle/wrapper/gradle-wrapper.jar | cut -d' ' -f1)
[[ $wrapper_hash == 55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c ]] ||
  fail "Gradle wrapper JAR hash changed"
grep -q '^distributionUrl=.*gradle-9\.4\.1-bin\.zip$' gradle/wrapper/gradle-wrapper.properties
grep -q '^distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb$' \
  gradle/wrapper/gradle-wrapper.properties
grep -q 'android:allowBackup="false"' modules/android/app/src/main/AndroidManifest.xml
grep -q 'android.permission.INTERNET' modules/android/app/src/main/AndroidManifest.xml
grep -q '<base-config cleartextTrafficPermitted="false"' \
  modules/android/app/src/main/res/xml/network_security_config.xml

scripts/generate-sbom.py --check
scripts/verify-source-size.sh

echo 'structure verified'
