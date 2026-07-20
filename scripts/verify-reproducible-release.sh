#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
evidence=$(mktemp -d "${TMPDIR:-/tmp}/codex-mobile-repro.XXXXXX")
trap 'rm -rf "$evidence"' EXIT

build() {
    "$root/gradlew" --no-build-cache clean :app:android:assembleRelease
}

build
cp "$root/app/android/build/outputs/apk/release/android-release.apk" "$evidence/first.apk"
build
cp "$root/app/android/build/outputs/apk/release/android-release.apk" "$evidence/second.apk"

cmp "$evidence/first.apk" "$evidence/second.apk"
shasum -a 256 "$evidence/first.apk"
echo "release APK is byte-for-byte reproducible"
