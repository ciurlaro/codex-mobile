#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

failed=0
while IFS= read -r source; do
  test -f "$source" || continue
  lines=$(wc -l < "$source" | tr -d ' ')
  if (( lines > 300 )); then
    printf 'source exceeds 300 lines: %s (%s)\n' "$source" "$lines" >&2
    failed=1
  fi
done < <(git ls-files --cached --others --exclude-standard '*.kt' '*.kts' '*.gradle')

generated=modules/multiplatform/codex-agent-runtime/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/generated
while IFS= read -r source; do
  test -f "$source" || continue
  lines=$(wc -l < "$source" | tr -d ' ')
  if (( lines < 100 || lines > 300 )); then
    printf 'generated source must have 100-300 lines: %s (%s)\n' "$source" "$lines" >&2
    failed=1
  fi
done < <(git ls-files --cached --others --exclude-standard "$generated/*.kt")

(( failed == 0 ))
echo 'source sizes verified'
