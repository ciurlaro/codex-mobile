#!/usr/bin/env bash
set -euo pipefail

artifacts=${1:-runtime-tests}
app=$(find "$artifacts" -type f -name app-debug.apk -print -quit)
properties="$artifacts/gradle.properties"
test -f "$app" && test -f "$properties"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unzip -qj "$app" 'lib/arm64-v8a/libcodex_app_server.so' -d "$work"
runtime="$work/libcodex_app_server.so"
chmod 755 "$runtime"

expected=$(sed -n 's/^codexMobile\.codexBinarySha256=//p' "$properties")
[[ $expected =~ ^[0-9a-f]{64}$ ]]
printf '%s  %s\n' "$expected" "$runtime" | sha256sum -c -

home="$work/home"
temporary="$work/tmp"
codex_home="$work/codex"
certificate=/etc/ssl/certs/ca-certificates.crt
mkdir -p "$home" "$temporary" "$codex_home"
test -s "$certificate"

initialize='{"id":1,"method":"initialize","params":{"clientInfo":{"name":"native_runtime_smoke","title":"Native Runtime Smoke","version":"1"}}}'
initialized='{"method":"initialized","params":{}}'
for cycle in 1 2; do
  response=$(
    cd "$home"
    printf '%s\n%s\n' "$initialize" "$initialized" |
      timeout --kill-after=5s 30s env -i \
        PATH=/usr/bin:/bin \
        LANG=C.UTF-8 \
        HOME="$home" \
        TMPDIR="$temporary" \
        CODEX_HOME="$codex_home" \
        CODEX_SQLITE_HOME="$codex_home" \
        SSL_CERT_FILE="$certificate" \
        NO_COLOR=1 \
        "$runtime"
  )
  jq -s -e --arg home "$codex_home" '
    any(.[]; .id == 1 and .result.codexHome == $home and
      .result.platformFamily == "unix" and .result.platformOs == "linux")
  ' <<<"$response" >/dev/null
  test -s "$codex_home/logs_2.sqlite"
  printf 'Native App Server cycle %s passed.\n' "$cycle"
done
