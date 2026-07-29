#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
evidence=$(mktemp -d "${TMPDIR:-/tmp}/codex-mobile-repro.XXXXXX")
trap 'rm -rf "$evidence"' EXIT

build() {
    "$root/gradlew" --no-build-cache clean :app:assembleRelease
}

baseline=${1:-}
if [[ -n "$baseline" ]]; then
    [[ -f "$baseline" ]] || { echo "baseline release APK does not exist: $baseline" >&2; exit 1; }
    cp "$baseline" "$evidence/first.apk"
else
    build
    cp "$root/modules/android/app/build/outputs/apk/release/app-release.apk" "$evidence/first.apk"
fi
build
cp "$root/modules/android/app/build/outputs/apk/release/app-release.apk" "$evidence/second.apk"

cmp "$evidence/first.apk" "$evidence/second.apk"
shasum -a 256 "$evidence/first.apk"
echo "release APK is byte-for-byte reproducible"
