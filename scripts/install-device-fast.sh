#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
provider_root=${CODEX_MOBILE_PROVIDER_ROOT:-"$root/../codex-mobile-plugins"}
android_home=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
java_home=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
build_tools="$android_home/build-tools/36.0.0"
adb="$android_home/platform-tools/adb"
apksigner="$build_tools/apksigner"
store_file=${CODEX_MOBILE_RELEASE_STORE_FILE:-"$HOME/Library/Application Support/Codex Mobile/Signing/codex-mobile-release.jks"}
key_alias=${CODEX_MOBILE_RELEASE_KEY_ALIAS:-codex-mobile-release}
keychain_account=${CODEX_MOBILE_RELEASE_KEYCHAIN_ACCOUNT:-$(id -un)}
keychain_service=${CODEX_MOBILE_RELEASE_KEYCHAIN_SERVICE:-io.github.ciurlaro.codexmobile.release}
package=io.github.ciurlaro.codexmobile

die() {
    echo "install-device-fast: $*" >&2
    exit 1
}

[[ $# -eq 0 ]] || die "usage: scripts/install-device-fast.sh"
[[ -d $provider_root ]] || die "provider repository was not found at $provider_root"
[[ -x $java_home/bin/java ]] || die "Java 17 was not found at $java_home"
[[ -x $adb ]] || die "adb was not found at $adb"
[[ -x $apksigner ]] || die "apksigner was not found at $apksigner"
[[ -f $store_file ]] || die "release keystore was not found at $store_file"

connected=$("$adb" devices -l)
devices=()
while IFS= read -r candidate; do
    [[ -n $candidate ]] && devices+=("$candidate")
done < <(awk '$2 == "device" && $1 !~ /^emulator-/ { print $1 }' <<<"$connected")

if [[ -n ${ANDROID_SERIAL:-} ]]; then
    serial=$ANDROID_SERIAL
    [[ $serial != emulator-* ]] || die "ANDROID_SERIAL names an emulator, not a phone"
    found=false
    for candidate in "${devices[@]}"; do
        [[ $candidate == "$serial" ]] && found=true
    done
    $found || die "ANDROID_SERIAL=$serial is not an authorized physical device"
elif [[ ${#devices[@]} -eq 1 ]]; then
    serial=${devices[0]}
elif [[ ${#devices[@]} -eq 0 ]]; then
    die "no authorized physical phone found; connect it and accept the USB debugging prompt"
else
    die "more than one physical phone is connected; set ANDROID_SERIAL to the intended device"
fi

store_password=${CODEX_MOBILE_RELEASE_STORE_PASSWORD:-}
if [[ -z $store_password ]]; then
    command -v security >/dev/null || die "set CODEX_MOBILE_RELEASE_STORE_PASSWORD"
    store_password=$(security find-generic-password \
        -a "$keychain_account" \
        -s "$keychain_service" \
        -w) || die "release password was not found in macOS Keychain"
fi
key_password=${CODEX_MOBILE_RELEASE_KEY_PASSWORD:-$store_password}

export JAVA_HOME=$java_home
export ANDROID_HOME=$android_home
export CODEX_MOBILE_RELEASE_STORE_PASSWORD=$store_password
export CODEX_MOBILE_RELEASE_KEY_PASSWORD=$key_password

staging=$(mktemp -d "${TMPDIR:-/tmp}/codex-mobile-fast-install.XXXXXX")
cleanup() {
    rm -rf -- "$staging"
    unset store_password key_password CODEX_MOBILE_RELEASE_STORE_PASSWORD CODEX_MOBILE_RELEASE_KEY_PASSWORD
}
trap cleanup EXIT

"$root/gradlew" -p "$root" --no-daemon \
    "-PcodexMobile.providerBuild=$provider_root" \
    :app:assembleDebug

source="$root/modules/android/app/build/outputs/apk/debug/app-debug.apk"
signed="$staging/codex-mobile.apk"
expected=$(<"$root/release-signing-certificate.sha256")

[[ -f $source ]] || die "missing debug APK: $source"
"$apksigner" sign \
    --ks "$store_file" \
    --ks-pass env:CODEX_MOBILE_RELEASE_STORE_PASSWORD \
    --ks-key-alias "$key_alias" \
    --key-pass env:CODEX_MOBILE_RELEASE_KEY_PASSWORD \
    --out "$signed" \
    "$source"
certificates=$("$apksigner" verify --print-certs "$signed" |
    awk -F': ' '/certificate SHA-256 digest/ { print tolower($2) }')
[[ $certificates == "$expected" ]] || die "signed APK certificate does not match the installed release"

model=$("$adb" -s "$serial" shell getprop ro.product.model | tr -d '\r')
echo "fast local install on ${model:-unknown} ($serial)"
"$adb" -s "$serial" install -r "$signed"

installed=$("$adb" -s "$serial" shell pm path "$package" | tr -d '\r')
grep -q '/base\.apk$' <<<"$installed" || die "Android did not report the installed base APK"
if grep -q '/split_provider_.*\.apk$' <<<"$installed"; then
    die "legacy provider splits remain after the full APK update"
fi

"$adb" -s "$serial" shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null
echo "Fast debug build installed without clearing app data. Full tests, lint, Android tests, and release shrinking were skipped."
