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

grep -q '/split_provider_documents\.apk$' <<<"$(installed_splits)" ||
    die "install and verify the Documents provider before running this check"

set +e
uninstall_output=$("$adb" -s "$serial" shell am instrument -w -r \
    -e class "$test_class#signedProviderUninstallStartsARecoverablePackageUpdate" \
    -e providerUninstallE2e true \
    -e workspacePath "$workspace" \
    "$test_runner" 2>&1)
uninstall_status=$?
set -e

for _ in {1..30}; do
    if ! grep -q '/split_provider_documents\.apk$' <<<"$(installed_splits)"; then break; fi
    sleep 1
done
if grep -q '/split_provider_documents\.apk$' <<<"$(installed_splits)"; then
    printf '%s\n' "$uninstall_output" >&2
    die "Documents split remained installed"
fi

if ! grep -q 'OK (1 test)' <<<"$uninstall_output"; then
    for _ in {1..30}; do
        "$adb" -s "$serial" shell uiautomator dump /sdcard/codex-mobile-window.xml >/dev/null 2>&1 || true
        window_xml=$("$adb" -s "$serial" shell cat /sdcard/codex-mobile-window.xml 2>/dev/null | tr -d '\r')
        if grep -q 'text="Extensions"' <<<"$window_xml" && grep -q 'text="Documents removed"' <<<"$window_xml"; then
            break
        fi
        sleep 1
    done
    grep -q 'text="Extensions"' <<<"$window_xml" || {
        printf '%s\n' "$uninstall_output" >&2
        die "Extensions was not restored after package restart (instrumentation status $uninstall_status)"
    }
    grep -q 'text="Documents removed"' <<<"$window_xml" || die "removal completion was not shown"
fi

"$adb" -s "$serial" shell am instrument -w -r \
    -e class "$test_class#signedProviderRemovalIsReconciledAfterRestart" \
    -e providerRemovalVerifyE2e true \
    -e workspacePath "$workspace" \
    "$test_runner"

echo "Documents uninstall recovered the visible app and reconciled provider state."
