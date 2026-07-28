#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

failed=0
source_roots=(
  agent app app-server-client build-logic core platform provider-api runtime-host
  build.gradle.kts settings.gradle.kts
)
while IFS= read -r -d '' source; do
  lines=$(wc -l < "$source" | tr -d ' ')
  if (( lines > 300 )); then
    printf 'source exceeds 300 lines: %s (%s)\n' "$source" "$lines" >&2
    failed=1
  fi
done < <(
  find "${source_roots[@]}" \
    -path '*/build' -prune -o \
    -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.gradle' \) -print0
)

generated=app-server-client/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/generated
while IFS= read -r -d '' source; do
  lines=$(wc -l < "$source" | tr -d ' ')
  if (( lines < 100 || lines > 300 )); then
    printf 'generated source must have 100-300 lines: %s (%s)\n' "$source" "$lines" >&2
    failed=1
  fi
done < <(find "$generated" -type f -name '*.kt' -print0)

(( failed == 0 ))
echo 'source sizes verified'
