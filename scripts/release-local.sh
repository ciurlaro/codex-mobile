#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
provider_root=${CODEX_MOBILE_PROVIDER_ROOT:-"$root/../codex-mobile-plugins"}
provider_revision=$(awk -F= '$1 == "codexMobile.providerRevision" { print $2; exit }' "$root/gradle.properties")

die() {
    echo "release-local: $*" >&2
    exit 1
}

case "${1:-}" in
    "") reproducible=false ;;
    --reproducible) reproducible=true ;;
    *) die "usage: scripts/release-local.sh [--reproducible]" ;;
esac
[[ $# -le 1 ]] || die "usage: scripts/release-local.sh [--reproducible]"

default_java_home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
if [[ -n ${JAVA_HOME:-} ]]; then
    java_home=$JAVA_HOME
elif [[ -x $default_java_home/bin/java ]]; then
    java_home=$default_java_home
elif [[ -x /usr/libexec/java_home ]]; then
    java_home=$(/usr/libexec/java_home -v 17)
else
    die "Java 17 was not found; set JAVA_HOME"
fi

android_home=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
store_file=${CODEX_MOBILE_RELEASE_STORE_FILE:-"$HOME/Library/Application Support/Codex Mobile/Signing/codex-mobile-release.jks"}
key_alias=${CODEX_MOBILE_RELEASE_KEY_ALIAS:-codex-mobile-release}
keychain_account=${CODEX_MOBILE_RELEASE_KEYCHAIN_ACCOUNT:-$(id -un)}
keychain_service=${CODEX_MOBILE_RELEASE_KEYCHAIN_SERVICE:-io.github.ciurlaro.codexmobile.release}

[[ -x $java_home/bin/java ]] || die "Java was not found at $java_home"
[[ -f $android_home/platforms/android-37/android.jar ||
   -f $android_home/platforms/android-37.0/android.jar ]] ||
    die "Android platform 37 was not found under $android_home"
[[ -f $store_file ]] || die "release keystore was not found at $store_file"
[[ -d $provider_root/.git ]] || die "provider repository was not found at $provider_root"
[[ $(git -C "$provider_root" rev-parse HEAD) == "$provider_revision" ]] ||
    die "provider repository must be at $provider_revision"
[[ -z $(git -C "$provider_root" status --porcelain) ]] || die "provider repository has uncommitted changes"

store_password=${CODEX_MOBILE_RELEASE_STORE_PASSWORD:-}
if [[ -z $store_password ]]; then
    command -v security >/dev/null || die "macOS Keychain command 'security' was not found"
    store_password=$(security find-generic-password \
        -a "$keychain_account" \
        -s "$keychain_service" \
        -w) || die "release password was not found in macOS Keychain"
fi
key_password=${CODEX_MOBILE_RELEASE_KEY_PASSWORD:-$store_password}

export JAVA_HOME=$java_home
export ANDROID_HOME=$android_home
export CODEX_MOBILE_RELEASE_STORE_FILE=$store_file
export CODEX_MOBILE_RELEASE_STORE_PASSWORD=$store_password
export CODEX_MOBILE_RELEASE_KEY_ALIAS=$key_alias
export CODEX_MOBILE_RELEASE_KEY_PASSWORD=$key_password
trap 'unset store_password key_password CODEX_MOBILE_RELEASE_STORE_PASSWORD CODEX_MOBILE_RELEASE_KEY_PASSWORD' EXIT

cd "$root"
./gradlew --no-daemon "-PcodexMobile.providerBuild=$provider_root" \
    test assembleDebug assembleDebugAndroidTest lint assembleRelease bundleRelease
scripts/verify-release.sh

if $reproducible; then
    scripts/verify-reproducible-release.sh
fi

echo "release APK: $root/app/android/build/outputs/apk/release/android-release.apk"
