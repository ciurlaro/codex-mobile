#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
apk=${1:-"$root/app/android/build/outputs/apk/release/android-release.apk"}
build_tools=${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}/build-tools/36.0.0
apksigner="$build_tools/apksigner"
aapt2="$build_tools/aapt2"

test -f "$apk"
test -x "$apksigner"
test -x "$aapt2"
test -s "$root/app/android/build/outputs/mapping/release/mapping.txt"

"$apksigner" verify --verbose "$apk" | grep -q 'Verified using v2 scheme.*true'
manifest=$("$aapt2" dump xmltree "$apk" --file AndroidManifest.xml)
grep -q 'versionCode.*=3' <<<"$manifest"
grep -q 'versionName.*="0.2.0-preview.1"' <<<"$manifest"
grep -q 'allowBackup.*=false' <<<"$manifest"
grep -q 'usesCleartextTraffic.*=false' <<<"$manifest"
grep -q 'networkSecurityConfig' <<<"$manifest"
grep -q 'MANAGE_EXTERNAL_STORAGE' <<<"$manifest"
grep -q 'CodexForegroundService' <<<"$manifest"
test "$(grep -c 'exported.*=true' <<<"$manifest")" -eq 1
! grep -q 'Step03ApprovalTestActivity' <<<"$manifest"
! grep -q 'debuggable.*=true' <<<"$manifest"

contents=$(unzip -Z1 "$apk")
grep -qx 'lib/arm64-v8a/libcodex_app_server.so' <<<"$contents"
for library in \
  libcodex_mutool.so libcodex_tesseract.so \
  libcodex_officecli.so libcodex_officecli_musl.so libcodex_officecli_gcc.so libcodex_officecli_cxx.so \
  libcodex_tgcli.so libcodex_node.so libcodex_z.so libcodex_cares.so libcodex_sqlite3.so \
  libcodex_crypto.so libcodex_ssl.so libcodex_icudata.so libcodex_icui18n.so libcodex_icuuc.so libcodex_cxx.so; do
  grep -qx "lib/arm64-v8a/$library" <<<"$contents"
done
grep -qx 'assets/openai-codex-LICENSE.txt' <<<"$contents"
grep -qx 'assets/openai-codex-NOTICE.txt' <<<"$contents"
grep -qx 'assets/native-tools-NOTICE.txt' <<<"$contents"
grep -qx 'assets/runtime/tgcli.zip' <<<"$contents"
grep -qx 'assets/runtime/officecli/officecli' <<<"$contents"
grep -qx 'assets/runtime/tessdata/eng.traineddata' <<<"$contents"
grep -qx 'assets/runtime/licenses/mupdf-COPYING.txt' <<<"$contents"
grep -qx 'assets/runtime/licenses/officecli-LICENSE.txt' <<<"$contents"
grep -qx 'assets/runtime/licenses/tgcli-LICENSE.txt' <<<"$contents"
grep -qx 'assets/codex/skills/local-documents/SKILL.md' <<<"$contents"
grep -qx 'assets/codex/skills/tgcli/SKILL.md' <<<"$contents"
! grep -q '^lib/\(x86\|x86_64\|armeabi-v7a\)/' <<<"$contents"
binary_hash=$(unzip -p "$apk" lib/arm64-v8a/libcodex_app_server.so | shasum -a 256 | cut -d' ' -f1)
test "$binary_hash" = '09d6a41d6189b14317ec5d556251e5195e9a4235c28867fc75ee5c1d54be02cd'

"$root/scripts/generate-sbom.py" --check
test -s "$root/gradle/verification-metadata.xml"
test -s "$root/app/android/gradle.lockfile"
test -s "$root/agent/codex/gradle.lockfile"
test -s "$root/core/gradle.lockfile"
test -s "$root/platform/android/gradle.lockfile"

echo "release verified"
