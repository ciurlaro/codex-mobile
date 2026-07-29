#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
property() { awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$root/gradle.properties"; }
version_code=$(property codexMobile.versionCode)
version_name=$(property codexMobile.versionName)
codex_sha=$(property codexMobile.codexBinarySha256)
apk=${1:-"$root/modules/android/app/build/outputs/apk/release/app-release.apk"}
aab=${2:-"$root/modules/android/app/build/outputs/bundle/release/app-release.aab"}
tools=${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}/build-tools/36.0.0

test -f "$apk" && test -f "$aab"
test -x "$tools/apksigner" && test -x "$tools/aapt2" && test -x "$tools/dexdump"
test -s "$root/modules/android/app/build/outputs/mapping/release/mapping.txt"
"$tools/apksigner" verify --verbose "$apk" | grep -q 'Verified using v2 scheme.*true'
manifest=$("$tools/aapt2" dump xmltree "$apk" --file AndroidManifest.xml)
grep -q "versionCode.*=$version_code" <<<"$manifest"
grep -q "versionName.*=\"$version_name\"" <<<"$manifest"
grep -q 'allowBackup.*=false' <<<"$manifest"
grep -q 'usesCleartextTraffic.*=false' <<<"$manifest"
! grep -q 'REQUEST_INSTALL_PACKAGES' <<<"$manifest"
grep -q 'TelegramSettingsActivity' <<<"$manifest"
grep -q 'CodexForegroundService' <<<"$manifest"
test "$(grep -c 'exported.*=true' <<<"$manifest")" -eq 1
! grep -q 'debuggable.*=true' <<<"$manifest"

host_dex=$(mktemp)
trap 'rm -f "$host_dex"' EXIT
"$tools/dexdump" -f "$apk" > "$host_dex"
grep -Fq "Class descriptor  : 'Lio/github/ciurlaro/codexmobile/provider/api/CodexMobileProvider;'" "$host_dex"
grep -Fq "Class descriptor  : 'Lio/github/ciurlaro/codexmobile/platform/android/DocumentsProvider;'" "$host_dex"
grep -Fq "Class descriptor  : 'Lio/github/ciurlaro/codexmobile/platform/android/TelegramProvider;'" "$host_dex"

base=$(unzip -Z1 "$apk")
bundle=$(unzip -Z1 "$aab")
expected_native=$'lib/arm64-v8a/libandroidx.graphics.path.so\nlib/arm64-v8a/libcodex_app_server.so\nlib/arm64-v8a/libmlkit_google_ocr_pipeline.so\nlib/arm64-v8a/libpdfium.so\nlib/arm64-v8a/libpdfiumandroid.so\nlib/arm64-v8a/libratex_ffi.so\nlib/arm64-v8a/libtdjsonjava.so'
test "$(grep '^lib/.*\.so$' <<<"$base" | sort)" = "$expected_native"
expected_bundle_native=$'base/lib/arm64-v8a/libandroidx.graphics.path.so\nbase/lib/arm64-v8a/libcodex_app_server.so\nbase/lib/arm64-v8a/libmlkit_google_ocr_pipeline.so\nbase/lib/arm64-v8a/libpdfium.so\nbase/lib/arm64-v8a/libpdfiumandroid.so\nbase/lib/arm64-v8a/libratex_ffi.so\nbase/lib/arm64-v8a/libtdjsonjava.so'
test "$(grep '/lib/.*\.so$' <<<"$bundle" | sort)" = "$expected_bundle_native"
! grep -q '^base/assets/codex/plugins/' <<<"$bundle"
! grep -q '^assets/codex/plugins/' <<<"$base"
! grep -q '^lib/\(x86\|x86_64\|armeabi-v7a\)/' <<<"$base"
test "$(grep '/manifest/AndroidManifest.xml$' <<<"$bundle")" = 'base/manifest/AndroidManifest.xml'

actual=$(unzip -p "$apk" lib/arm64-v8a/libcodex_app_server.so | shasum -a 256 | cut -d' ' -f1)
test "$actual" = "$codex_sha"
"$root/scripts/generate-sbom.py" --check
for lock in gradle.lockfile settings-gradle.lockfile \
  modules/multiplatform/codex-agent-runtime/gradle.lockfile \
  modules/multiplatform/extension-provider-api/gradle.lockfile \
  modules/android/extension-host/gradle.lockfile modules/android/app/gradle.lockfile; do
  test -s "$root/$lock"
done
test -s "$root/gradle/verification-metadata.xml"

echo 'release verified'
