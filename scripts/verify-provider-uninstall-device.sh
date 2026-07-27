#!/usr/bin/env bash
set -euo pipefail

android_sdk=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
adb="$android_sdk/platform-tools/adb"
package=io.github.ciurlaro.codexmobile
test_runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
test_class="$package.app.ProviderInstallDeviceTest"

die() {
    echo "verify-provider-uninstall-device: $*" >&2
    exit 1
}

[[ $# -eq 1 ]] || die "usage: scripts/verify-provider-uninstall-device.sh DEVICE_WORKSPACE_PATH"
[[ -x $adb ]] || die "adb was not found at $adb"
workspace=$1

serial=${ANDROID_SERIAL:-}
if [[ -z $serial ]]; then
    devices=()
    while IFS= read -r candidate; do
        [[ -n $candidate ]] && devices+=("$candidate")
    done < <("$adb" devices | awk '$2 == "device" { print $1 }')
    [[ ${#devices[@]} -eq 1 ]] || die "connect one device or set ANDROID_SERIAL"
    serial=${devices[0]}
fi

installed_splits() {
    "$adb" -s "$serial" shell pm path "$package" | tr -d '\r'
}

if grep -q '/split_provider_.*\.apk$' <<<"$(installed_splits)"; then
    die "install the monolithic Codex Mobile APK before running this check"
fi

uninstall_output=$("$adb" -s "$serial" shell am instrument -w -r \
    -e class "$test_class#bundledProviderUninstallKeepsTheAppProcessAlive" \
    -e providerUninstallE2e true \
    -e workspacePath "$workspace" \
    "$test_runner")
grep -q 'OK (1 test)' <<<"$uninstall_output" || { printf '%s\n' "$uninstall_output" >&2; exit 1; }
if grep -q '/split_provider_.*\.apk$' <<<"$(installed_splits)"; then
    die "a provider split appeared during uninstall"
fi

"$adb" -s "$serial" shell am instrument -w -r \
    -e class "$test_class#signedProviderRemovalIsReconciledAfterRestart" \
    -e providerRemovalVerifyE2e true \
    -e workspacePath "$workspace" \
    "$test_runner"

echo "Documents uninstall kept the app process alive and reconciled provider state."
