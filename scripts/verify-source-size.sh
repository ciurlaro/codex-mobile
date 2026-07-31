#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

failed=0
generated=modules/multiplatform/codex-shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/generated

while IFS= read -r -d '' source; do
  [[ $source == "$generated/"* ]] && continue
  lines=$(wc -l < "$source" | tr -d ' ')
  if (( lines > 300 )); then
    printf 'handwritten Kotlin exceeds 300 lines: %s (%s)\n' "$source" "$lines" >&2
    failed=1
  fi
done < <(
  find modules \
    -path '*/build' -prune -o \
    -type f \( -name '*.kt' -o -name '*.kts' \) -print0
)

while IFS= read -r -d '' source; do
  lines=$(wc -l < "$source" | tr -d ' ')
  if (( lines < 100 || lines > 300 )); then
    printf 'generated protocol shard must have 100-300 lines: %s (%s)\n' "$source" "$lines" >&2
    failed=1
  fi
done < <(find "$generated" -type f -name '*.kt' -print0)

if (( failed != 0 )); then
  exit 1
fi
echo 'source sizes verified'
