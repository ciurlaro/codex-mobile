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

runtime=modules/multiplatform/codex-agent-runtime
provider=modules/multiplatform/extension-provider-api
app=modules/android/app
extensions=modules/android/extension-host
runtime_kotlin="$runtime/src/commonMain/kotlin/io/github/ciurlaro/codexmobile"
provider_kotlin="$provider/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api"
app_kotlin="$app/src/main/kotlin/io/github/ciurlaro/codexmobile/app"
extensions_kotlin="$extensions/src/main/kotlin/io/github/ciurlaro/codexmobile/extension/host"
generator=tools/protocol-generator

reject_unexpected_kotlin() {
  local label=$1 root=$2
  shift 2
  while IFS= read -r source; do
    local accepted=1
    for prefix in "$@"; do
      if [[ "$source" == "$prefix/"* ]]; then
        accepted=0
        break
      fi
    done
    if (( accepted != 0 )); then
      echo "$label Kotlin source uses an unsupported source root or package: $source" >&2
      exit 1
    fi
  done < <(find "$root" -type f -name '*.kt' -print)
}

reject_unexpected_kotlin runtime "$runtime/src" \
  "$runtime/src/commonMain/kotlin/io/github/ciurlaro/codexmobile" \
  "$runtime/src/commonTest/kotlin/io/github/ciurlaro/codexmobile"
reject_unexpected_kotlin provider "$provider/src" \
  "$provider/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api" \
  "$provider/src/commonTest/kotlin/io/github/ciurlaro/codexmobile/provider/api"
reject_unexpected_kotlin app "$app/src" \
  "$app/src/main/kotlin/io/github/ciurlaro/codexmobile/app" \
  "$app/src/test/kotlin/io/github/ciurlaro/codexmobile/app" \
  "$app/src/androidTest/kotlin/io/github/ciurlaro/codexmobile/app"
reject_unexpected_kotlin extension-host "$extensions/src" \
  "$extensions/src/main/kotlin/io/github/ciurlaro/codexmobile/extension/host" \
  "$extensions/src/test/kotlin/io/github/ciurlaro/codexmobile/extension/host" \
  "$extensions/src/androidTest/kotlin/io/github/ciurlaro/codexmobile/extension/host"
reject_unexpected_kotlin protocol-generator "$generator/src" \
  "$generator/src/main/kotlin/io/github/ciurlaro/codexmobile/appserver/generator" \
  "$generator/src/test/kotlin/io/github/ciurlaro/codexmobile/appserver/generator"
reject_unexpected_kotlin build-logic build-logic/src \
  build-logic/src/main/kotlin build-logic/src/test/kotlin

required=(
  README.md LICENSE LICENSES/MLKIT-EXCEPTION.txt THIRD_PARTY_NOTICES.md CONTRIBUTING.md SECURITY.md
  release-signing-certificate.sha256 settings.gradle.kts gradle.properties gradle.lockfile
  gradle/verification-metadata.xml build-logic/src/main/kotlin/codexmobile.android-kmp-library.gradle.kts
  "$runtime/build.gradle.kts" "$runtime/gradle.lockfile" "$runtime/protocol/schema/provenance.json"
  "$runtime/protocol/schema/codex_app_server_protocol.v2.schemas.json"
  "$runtime/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/AppServerProtocolIdentity.kt"
  "$runtime/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/runtime/process/CodexAppServerRuntime.kt"
  "$runtime/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/runtime/networking/LoopbackConnectProxy.kt"
  "$runtime/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/agent/AgentClient.kt"
  "$runtime/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/agent/CodexAgentClient.kt"
  "$provider/settings.gradle.kts" "$provider/build.gradle.kts" "$provider/gradle.lockfile"
  "$provider/gradle/verification-metadata.xml"
  "$provider/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/lifecycle/CodexMobileProvider.kt"
  "$provider/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/models/ProviderModels.kt"
  "$provider/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/settings/ProviderSettings.kt"
  "$provider/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/tools/ProviderTools.kt"
  "$app/build.gradle.kts" "$app/provider-addon-rules.pro"
  "$app/src/main/kotlin/io/github/ciurlaro/codexmobile/app/runtime/bootstrap/AndroidRuntimeBootstrap.kt"
  "$extensions/build.gradle.kts"
  "$extensions/src/main/kotlin/io/github/ciurlaro/codexmobile/extension/host/providers/registry/AndroidProviderRegistry.kt"
  "$extensions/src/main/kotlin/io/github/ciurlaro/codexmobile/extension/host/tools/ProviderToolDispatcher.kt"
  "$extensions/src/main/kotlin/io/github/ciurlaro/codexmobile/extension/host/recovery/BuiltInMutationJournal.kt"
  docs/requirements.md docs/architecture.md docs/objects.md docs/decisions.md docs/security.md
  docs/privacy.md docs/release.md docs/sbom.cdx.json scripts/generate-sbom.py
  scripts/run-android-device-tests.sh scripts/verify-release.sh
)
for path in "${required[@]}"; do
  test -f "$path" || { echo "missing required file: $path" >&2; exit 1; }
done
test -x scripts/run-android-device-tests.sh || {
  echo "device-test runner must be executable" >&2
  exit 1
}
if find "$runtime/protocol" -mindepth 1 -maxdepth 1 -type f -print | grep -q .; then
  echo "protocol artifacts must live under protocol/schema" >&2
  exit 1
fi

expected_modules=$(printf '%s\n' \
  modules/android/app/build.gradle.kts \
  modules/android/extension-host/build.gradle.kts \
  modules/multiplatform/codex-agent-runtime/build.gradle.kts \
  modules/multiplatform/extension-provider-api/build.gradle.kts)
actual_modules=$(find modules -mindepth 3 -maxdepth 3 -name build.gradle.kts -print | sort)
test "$actual_modules" = "$expected_modules" || {
  echo "production module set does not match the four-module architecture" >&2
  exit 1
}

while IFS= read -r source; do
  relative=${source#"$runtime_kotlin/"}
  case "$relative" in
    agent/AgentClient.kt|agent/CodexAgentClient.kt|agent/authentication/*.kt|agent/conversation/*.kt|\
    agent/extensions/*.kt|agent/hooks/*.kt|agent/models/*.kt|agent/notifications/*.kt|agent/tools/*.kt|\
    appserver/client/*.kt|appserver/protocol/*.kt|appserver/protocol/generated/*.kt|\
    appserver/protocol/serialization/*.kt|appserver/runtime/binary/*.kt|\
    appserver/runtime/configuration/*.kt|appserver/runtime/networking/*.kt|\
    appserver/runtime/process/*.kt|appserver/runtime/security/*.kt) ;;
    *) echo "runtime source is outside its responsibility folder: $source" >&2; exit 1 ;;
  esac
done < <(find "$runtime_kotlin" -type f -name '*.kt' -print)

while IFS= read -r source; do
  relative=${source#"$provider_kotlin/"}
  case "$relative" in lifecycle/*.kt|models/*.kt|settings/*.kt|tools/*.kt) ;;
    *) echo "provider API source is outside its responsibility folder: $source" >&2; exit 1 ;;
  esac
done < <(find "$provider_kotlin" -type f -name '*.kt' -print)

while IFS= read -r source; do
  relative=${source#"$extensions_kotlin/"}
  case "$relative" in
    catalog/*.kt|marketplace/*.kt|plugins/*.kt|providers/lifecycle/*.kt|providers/registry/*.kt|\
    providers/settings/*.kt|recovery/*.kt|secrets/*.kt|skills/*.kt|storage/*.kt|tools/*.kt) ;;
    *) echo "extension host source is outside its responsibility folder: $source" >&2; exit 1 ;;
  esac
done < <(find "$extensions_kotlin" -type f -name '*.kt' -print)

while IFS= read -r source; do
  relative=${source#"$app_kotlin/"}
  case "$relative" in
    composition/*.kt|lifecycle/*.kt|persistence/*.kt|presentation/coordinator/authentication/*.kt|\
    presentation/coordinator/chat/*.kt|presentation/coordinator/extensions/*.kt|\
    presentation/coordinator/hooks/*.kt|presentation/coordinator/session/*.kt|presentation/event/*.kt|\
    presentation/formatting/*.kt|presentation/input/*.kt|presentation/invocation/*.kt|\
    presentation/mapper/*.kt|presentation/model/*.kt|presentation/reducer/*.kt|presentation/selector/*.kt|\
    presentation/state/*.kt|presentation/validation/*.kt|presentation/viewmodel/AppViewModel*.kt|\
    runtime/bootstrap/*.kt|security/display/*.kt|security/navigation/*.kt|session/agent/*.kt|\
    session/background/*.kt|ui/authentication/*.kt|ui/chat/*.kt|ui/extensions/*.kt|ui/icons/*.kt|\
    ui/session/*.kt|ui/settings/*.kt|ui/shell/*.kt|ui/theme/*.kt|workspace/*.kt) ;;
    *) echo "app source is outside its responsibility folder: $source" >&2; exit 1 ;;
  esac
done < <(find "$app_kotlin" -type f -name '*.kt' -print)

test "$(find "$app_kotlin/runtime/bootstrap" -type f -name '*.kt' -print)" = \
  "$app_kotlin/runtime/bootstrap/AndroidRuntimeBootstrap.kt"
test ! -d "$runtime/src/androidMain"
test ! -d "$runtime/src/androidDeviceTest"
test ! -d "$app/src/debug"
! rg -q 'withDeviceTestBuilder|compileAndroidDeviceTest' build-logic modules README.md .github \
  --glob '!**/build/**' --glob '!**/gradle.lockfile'
if find core agent app-server-client runtime-host provider-api platform app \
  -path '*/build' -prune -o -path '*/.kotlin' -prune -o -path '*/src/*' -type f -print 2>/dev/null | grep -q .; then
  echo "legacy production source root remains" >&2
  exit 1
fi

for removed in core/build.gradle.kts agent/codex/build.gradle.kts app-server-client/build.gradle.kts \
  runtime-host/build.gradle.kts provider-api/build.gradle.kts app/android/build.gradle.kts \
  platform/android/build.gradle.kts; do
  test ! -e "$removed" || { echo "obsolete module remains: $removed" >&2; exit 1; }
done

grep -q 'includeBuild("modules/multiplatform/extension-provider-api")' settings.gradle.kts
grep -q 'project(":codex-agent-runtime").projectDir' settings.gradle.kts
grep -q 'project(":extension-host").projectDir' settings.gradle.kts
grep -q 'project(":app").projectDir' settings.gradle.kts
grep -q 'codex-agent-runtime = "0.145.0-1"' gradle/libs.versions.toml
grep -q 'extension-provider-api = "2.0.0"' gradle/libs.versions.toml
provider_revision=$(sed -n 's/^codexMobile.providerRevision=//p' gradle.properties)
[[ "$provider_revision" =~ ^[0-9a-f]{40}$ ]]
grep -Fq 'ref: ${{ steps.provider.outputs.revision }}' .github/workflows/verify.yml
reject_matches -n \
  '(?<!extension-)provider-api|app-server-client|agent/codex|app/android|platform/android/(src|build|gradle)|runtime-host|codexMobile\.providerApiBuild|io\.github\.ciurlaro\.codexmobile:provider-api' \
  README.md CONTRIBUTING.md SECURITY.md docs .github scripts build.gradle.kts settings.gradle.kts \
  --glob '!scripts/verify-structure.sh' --glob '!**/build/**' --pcre2
grep -q 'const val APP_SERVER_VERSION = "0.145.0"' \
  "$runtime/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/AppServerProtocolIdentity.kt"
test "$(shasum -a 256 "$runtime/protocol/schema/codex_app_server_protocol.v2.schemas.json" | cut -d' ' -f1)" = \
  '32b26f2ab3fb7a4a409db958f438f48b0ef106e3a01468f8618fdf65bc823cc4'

common="$runtime/src/commonMain"
reject_matches -n '^[[:space:]]*import[[:space:]]+(android|java)\.' "$common"
reject_matches -n '^[[:space:]]*import[[:space:]]+androidx\.(?!sqlite)' "$common" --pcre2
reject_matches -n 'ProcessBuilder|Runtime\.getRuntime\(\)\.exec' modules --glob '!**/build/**'
test "$(rg -l 'Process\.Builder\(' "$common" --glob '*.kt')" = \
  "$runtime/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/runtime/process/CodexAppServerRuntime.kt"
reject_matches -n 'class ProviderToolDispatcher' "$common"
grep -q 'class ProviderToolDispatcher' \
  "$extensions/src/main/kotlin/io/github/ciurlaro/codexmobile/extension/host/tools/ProviderToolDispatcher.kt"
reject_matches -n 'package io\.github\.ciurlaro\.codexmobile\.(core|agent\.codex)' modules --glob '*.kt'
reject_matches -n '^package io\.github\.ciurlaro\.codexmobile\.appserver\.(host|transport)' modules --glob '*.kt'
reject_matches -n '^package io\.github\.ciurlaro\.codexmobile\.platform\.android' "$extensions/src" --glob '*.kt'
reject_matches -n '^package (?!io\.github\.ciurlaro\.codexmobile\.provider\.api$)' \
  "$provider/src/commonMain" --glob '*.kt' --pcre2

grep -q 'interface CodexMobileProvider' "$provider/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/lifecycle/CodexMobileProvider.kt"
grep -q 'ProviderMutationJournal' "$provider/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/tools/ProviderTools.kt"
grep -q 'ProviderWorkspace' "$provider/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/provider/api/tools/ProviderTools.kt"
grep -q 'AndroidKeyStore' "$extensions/src/main/kotlin/io/github/ciurlaro/codexmobile/extension/host/secrets/AndroidProviderSecretStore.kt"
grep -q 'record.marketplaceRepository == CANONICAL_PROVIDER_REPOSITORY' \
  "$extensions/src/main/kotlin/io/github/ciurlaro/codexmobile/extension/host/providers/registry/AndroidProviderRegistry.kt"
reject_matches -n 'REQUEST_INSTALL_PACKAGES|UPDATE_PACKAGES_WITHOUT_USER_ACTION|PackageInstaller' \
  "$app/src/main" "$extensions/src/main" --glob '!**/build/**'

if find "$app/src/main" "$extensions/src/main" -path '*/build/*' -prune -o -type f \
  \( -name '*.js' -o -name '*.mjs' -o -name '*.cjs' -o -name '*.zip' -o -name '*.so' \) \
  ! -name libcodex_app_server.so -print | grep -q .; then
  echo "unexpected runtime payload in the host" >&2
  exit 1
fi

grep -q 'android:allowBackup="false"' "$app/src/main/AndroidManifest.xml"
grep -q '<base-config cleartextTrafficPermitted="false"' "$app/src/main/res/xml/network_security_config.xml"
reject_matches -n '@Ignore|TODO[[:space:]]*\(' --glob '*.kt' modules --glob '!**/build/**'
reject_matches -n '(^|[^[:alnum:]_])(Log\.[vdiwe]|println|print|System\.(out|err))[[:space:]]*\(' \
  --glob '*.kt' "$runtime/src/commonMain" "$extensions/src/main" "$app/src/main"

wrapper_hash=$(openssl dgst -sha256 -r gradle/wrapper/gradle-wrapper.jar | cut -d' ' -f1)
test "$wrapper_hash" = '55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c'
grep -q '^distributionUrl=.*gradle-9\.4\.1-bin\.zip$' gradle/wrapper/gradle-wrapper.properties
scripts/generate-sbom.py --check
scripts/verify-source-size.sh

echo 'structure verified'
