#!/usr/bin/env bash
set -euo pipefail

artifacts=${1:-device-tests}
mode=${2:-full}
app=$(find "$artifacts" -type f -name app-debug.apk -print -quit)
app_test=$(find "$artifacts" -type f -name app-debug-androidTest.apk -print -quit)
test -f "$app" && test -f "$app_test"

run_test() {
  local output status
  set +e
  output=$(adb shell am instrument -w -r \
    -e class "$1" \
    io.github.ciurlaro.codexmobile.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
  status=$?
  set -e
  printf '%s\n' "$output"
  if ((status != 0)) || ! grep -q '^OK (' <<<"$output"; then
    adb logcat -d -b all -v threadtime -t 1000 || true
    return 1
  fi
}

adb install -r "$app"
adb install -r "$app_test"
adb shell cmd package compile -f -m speed io.github.ciurlaro.codexmobile.debug

case "$mode" in
  full)
    tests=(
      'io.github.ciurlaro.codexmobile.app.RuntimeBootstrapDeviceTest#missingNonExecutableAndCorruptOverridesFailClosed'
      'io.github.ciurlaro.codexmobile.app.RuntimeBootstrapDeviceTest#successfulRuntimeInstallsCertificatePrivacyAndCleanupPolicies'
      'io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#runtimePackagingPreparationAndChecksum'
      'io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#processStartStopRestartAndUnexpectedExit'
    )
    ;;
  platform)
    tests=(
      'io.github.ciurlaro.codexmobile.app.RuntimeBootstrapDeviceTest#missingNonExecutableAndCorruptOverridesFailClosed'
      'io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#runtimePackagingPreparationAndChecksum'
      'io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#runtimeCredentialsComponentsAndLogsRemainPrivate'
    )
    ;;
  *)
    printf 'unknown smoke mode: %s\n' "$mode" >&2
    exit 2
    ;;
esac
for test_class in "${tests[@]}"; do
  run_test "$test_class"
done
