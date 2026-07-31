#!/usr/bin/env bash
set -euo pipefail

artifacts=${1:-runtime-tests}
app=$(find "$artifacts" -type f -name app-debug.apk -print -quit)
properties="$artifacts/gradle.properties"
test -f "$app" && test -f "$properties"

work=$(mktemp -d)
runtime_pid=
cleanup() {
  if [[ -n $runtime_pid ]]; then
    kill "$runtime_pid" 2>/dev/null || true
    wait "$runtime_pid" 2>/dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT
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
  input="$work/input-$cycle"
  output="$work/output-$cycle"
  mkfifo "$input" "$output"
  (
    cd "$home"
    exec timeout --kill-after=5s 30s env -i \
      PATH=/usr/bin:/bin \
      LANG=C.UTF-8 \
      HOME="$home" \
      TMPDIR="$temporary" \
      CODEX_HOME="$codex_home" \
      CODEX_SQLITE_HOME="$codex_home" \
      SSL_CERT_FILE="$certificate" \
      NO_COLOR=1 \
      "$runtime" <"$input" >"$output"
  ) &
  runtime_pid=$!
  exec 3>"$input"
  exec 4<"$output"

  printf '%s\n' "$initialize" >&3
  IFS= read -r -t 30 response <&4
  jq -e --arg home "$codex_home" '
    .id == 1 and .result.codexHome == $home and
      .result.platformFamily == "unix" and .result.platformOs == "linux"
  ' <<<"$response" >/dev/null
  printf '%s\n' "$initialized" >&3
  exec 3>&-
  while IFS= read -r -t 30 _ <&4; do :; done
  wait "$runtime_pid"
  runtime_pid=
  exec 4<&-
  test -s "$codex_home/logs_2.sqlite"
  printf 'Native App Server cycle %s passed.\n' "$cycle"
done
