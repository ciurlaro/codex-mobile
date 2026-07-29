#!/usr/bin/env bash
set -euo pipefail

artifacts=${1:-device-tests}
app=$(find "$artifacts" -type f -name app-debug.apk -print -quit)
app_test=$(find "$artifacts" -type f -name app-debug-androidTest.apk -print -quit)
extension_test=$(find "$artifacts" -type f -name extension-host-debug-androidTest.apk -print -quit)
test -f "$app" && test -f "$app_test" && test -f "$extension_test"

run_instrumentation() {
  local output
  output=$(adb shell am instrument -w -r "$@")
  printf '%s\n' "$output"
  grep -q '^OK (' <<< "$output"
}

adb install -r "$app"
adb install -r "$app_test"
adb shell mkdir -p /sdcard/Download/codex-mobile-ci
adb shell appops set io.github.ciurlaro.codexmobile.debug MANAGE_EXTERNAL_STORAGE allow
run_instrumentation \
  -e class 'io.github.ciurlaro.codexmobile.app.RuntimeBootstrapDeviceTest,io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#runtimePackagingPreparationAndChecksum,io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#processStartStopRestartAndUnexpectedExit,io.github.ciurlaro.codexmobile.app.CodexRuntimeDeviceTest#authenticationUsesPersistedAccountOrStartsDeviceFlow,io.github.ciurlaro.codexmobile.app.ForegroundLifecycleDeviceTest#bindingRecreationAndMultipleActivitiesKeepOneOwner' \
  io.github.ciurlaro.codexmobile.debug.test/androidx.test.runner.AndroidJUnitRunner
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
