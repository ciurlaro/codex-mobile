#!/usr/bin/env bash
set -euo pipefail

artifacts=${1:-device-tests}
app=$(find "$artifacts" -type f -name app-debug.apk -print -quit)
app_test=$(find "$artifacts" -type f -name app-debug-androidTest.apk -print -quit)
test -f "$app" && test -f "$app_test"

run_instrumentation() {
  local output status
  set +e
  output=$(adb shell am instrument -w -r "$@" 2>&1)
  status=$?
  set -e
  printf '%s\n' "$output"
  if ((status != 0)) || ! grep -q '^OK (' <<< "$output"; then
    adb logcat -d -b all -v threadtime -t 1000 || true
    return 1
  fi
}

adb install -r "$app"
adb install -r "$app_test"
adb shell cmd package compile -f -m speed io.github.ciurlaro.codexmobile.debug
adb shell cmd package compile -f -m speed io.github.ciurlaro.codexmobile.debug.test

run_instrumentation \
  -e class 'io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#runtimePackagingPreparationAndChecksum' \
  io.github.ciurlaro.codexmobile.debug.test/androidx.test.runner.AndroidJUnitRunner
