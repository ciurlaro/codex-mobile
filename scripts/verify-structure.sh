#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

required=(
    README.md
    settings.gradle.kts
    core/build.gradle.kts
    agent/codex/build.gradle.kts
    platform/android/build.gradle.kts
    app/android/build.gradle.kts
    docs/requirements.md
    docs/architecture.md
    docs/objects.md
    docs/decisions.md
    docs/testing.md
    docs/roadmap/01-runtime-premise.md
    docs/roadmap/02-read-only-authority.md
    docs/roadmap/03-controlled-mutation.md
    docs/roadmap/04-mutation-recovery.md
    docs/roadmap/05-background-lifecycle.md
    docs/roadmap/06-mvp-readiness.md
)

for path in "${required[@]}"; do
    test -f "$path" || { echo "missing required file: $path" >&2; exit 1; }
done

if grep -R -n -E '^[[:space:]]*import[[:space:]]+(android|androidx)\.' core/src; then
    echo "core must not import Android SDK or AndroidX types" >&2
    exit 1
fi

if grep -R -n 'ProcessHost' core; then
    echo "generic process hosting does not belong in core" >&2
    exit 1
fi

if grep -R -n 'org.jetbrains.kotlin.android' --include='*.kts' --include='*.toml' .; then
    echo "AGP built-in Kotlin is used for Android modules" >&2
    exit 1
fi

for step in docs/roadmap/0[1-6]-*.md; do
    grep -q '^## Exit gate\|^## Goal' "$step" || { echo "roadmap gate missing: $step" >&2; exit 1; }
    grep -q '^## Test matrix' "$step" || { echo "test matrix missing: $step" >&2; exit 1; }
done

echo "structure verified"
