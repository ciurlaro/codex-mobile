#!/usr/bin/env bash
set -euo pipefail

artifacts=${1:-device-tests}
app=$(find "$artifacts" -type f -name app-debug.apk -print -quit)
app_test=$(find "$artifacts" -type f -name app-debug-androidTest.apk -print -quit)
extension_test=$(find "$artifacts" -type f -name extension-host-debug-androidTest.apk -print -quit)
test -f "$app" && test -f "$app_test" && test -f "$extension_test"

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
adb shell mkdir -p /sdcard/Download/codex-mobile-ci
adb shell appops set io.github.ciurlaro.codexmobile.debug MANAGE_EXTERNAL_STORAGE allow
runtime_tests=(
  'io.github.ciurlaro.codexmobile.app.RuntimeBootstrapDeviceTest#missingNonExecutableAndCorruptOverridesFailClosed'
  'io.github.ciurlaro.codexmobile.app.RuntimeBootstrapDeviceTest#successfulRuntimeInstallsCertificatePrivacyAndCleanupPolicies'
  'io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#runtimePackagingPreparationAndChecksum'
  'io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#processStartStopRestartAndUnexpectedExit'
  'io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#authenticationUsesPersistedAccountOrStartsDeviceFlow'
  'io.github.ciurlaro.codexmobile.app.ForegroundLifecycleDeviceTest#bindingRecreationAndMultipleActivitiesKeepOneOwner'
)
for test_class in "${runtime_tests[@]}"; do
  run_instrumentation \
    -e class "$test_class" \
    io.github.ciurlaro.codexmobile.debug.test/androidx.test.runner.AndroidJUnitRunner
done
run_instrumentation \
  -e providerE2e true \
  -e workspacePath /sdcard/Download/codex-mobile-ci \
  -e class 'io.github.ciurlaro.codexmobile.app.ProviderInstallDeviceTest#bundledProvidersActivateThroughAppServerAndExecuteOnDevice' \
  io.github.ciurlaro.codexmobile.debug.test/androidx.test.runner.AndroidJUnitRunner
run_instrumentation \
  -e providerUninstallE2e true \
  -e workspacePath /sdcard/Download/codex-mobile-ci \
  -e class 'io.github.ciurlaro.codexmobile.app.ProviderInstallDeviceTest#bundledProviderUninstallKeepsTheAppProcessAlive' \
  io.github.ciurlaro.codexmobile.debug.test/androidx.test.runner.AndroidJUnitRunner
run_instrumentation \
  -e providerRemovalVerifyE2e true \
  -e workspacePath /sdcard/Download/codex-mobile-ci \
  -e class 'io.github.ciurlaro.codexmobile.app.ProviderInstallDeviceTest#signedProviderRemovalIsReconciledAfterRestart' \
  io.github.ciurlaro.codexmobile.debug.test/androidx.test.runner.AndroidJUnitRunner

adb install -r "$extension_test"
run_instrumentation \
  -e class 'io.github.ciurlaro.codexmobile.extension.host.AndroidProviderRegistryDeviceTest,io.github.ciurlaro.codexmobile.extension.host.AndroidProviderSecretStoreDeviceTest,io.github.ciurlaro.codexmobile.extension.host.BuiltInMutationJournalDeviceTest' \
  io.github.ciurlaro.codexmobile.extension.host.test/androidx.test.runner.AndroidJUnitRunner

bash "$artifacts/codex-mobile-plugins/scripts/run-android-device-tests.sh" "$artifacts"
