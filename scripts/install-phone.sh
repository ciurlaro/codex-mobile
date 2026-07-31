#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
android_home=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
adb="$android_home/platform-tools/adb"
apk="$root/build/modules/android/app/outputs/apk/release/app-release.apk"
package=io.github.ciurlaro.codexmobile

die() {
    echo "install-phone: $*" >&2
    exit 1
}

[[ $# -eq 0 ]] || die "usage: scripts/install-phone.sh"
[[ -x $adb ]] || die "adb was not found at $adb"

connected=$("$adb" devices -l)
devices=()
while IFS= read -r serial; do
    [[ -n $serial ]] && devices+=("$serial")
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
    die "no authorized physical phone found; connect and unlock it, then accept the USB debugging prompt"
else
    die "more than one physical phone is connected; set ANDROID_SERIAL to the intended device"
fi

model=$("$adb" -s "$serial" shell getprop ro.product.model | tr -d '\r')
echo "target phone: ${model:-unknown} ($serial)"

"$root/scripts/release-local.sh"
"$adb" -s "$serial" install -r "$apk"
installed_paths=$("$adb" -s "$serial" shell pm path "$package" | tr -d '\r')
grep -q '/base\.apk$' <<<"$installed_paths" ||
    die "Android did not report the installed package"
"$adb" -s "$serial" shell monkey \
    -p "$package" \
    -c android.intent.category.LAUNCHER \
    1 >/dev/null

echo "Codex Mobile updated in place and opened on ${model:-$serial}"
