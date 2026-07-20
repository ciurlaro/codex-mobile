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
    docs/security.md
    docs/privacy.md
    docs/release.md
    docs/sbom.cdx.json
    gradlew
    gradle/wrapper/gradle-wrapper.jar
    gradle/wrapper/gradle-wrapper.properties
    gradle/verification-metadata.xml
    settings-gradle.lockfile
    core/gradle.lockfile
    agent/codex/gradle.lockfile
    platform/android/gradle.lockfile
    app/android/gradle.lockfile
    app/android/proguard-rules.pro
    app/android/src/main/res/xml/network_security_config.xml
    scripts/generate-sbom.py
    scripts/install-phone.sh
    scripts/release-local.sh
    scripts/verify-release.sh
    scripts/verify-reproducible-release.sh
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

if grep -R -n -E '@Ignore|TODO[[:space:]]*\(' \
    core/src agent/codex/src platform/android/src app/android/src; then
    echo "roadmap gate tests must not be ignored or left as TODO" >&2
    exit 1
fi

if find core/src/main agent/codex/src/main platform/android/src/main app/android/src/main \
    -type f -name '*.kt' -exec grep -n -H -E \
    '(^|[^[:alnum:]_])(Log\.[vdiwe]|println|print|System\.(out|err))[[:space:]]*\(' {} +; then
    echo "production code must not emit content-bearing logs" >&2
    exit 1
fi

while IFS= read -r use; do
    revision=${use##*@}
    if [[ ! $revision =~ ^[0-9a-f]{40}$ ]]; then
        echo "CI action is not commit-pinned: $use" >&2
        exit 1
    fi
done < <(grep -R -h -o -E 'uses:[[:space:]]+[^[:space:]#]+' .github/workflows)

wrapper_hash=$(openssl dgst -sha256 -r gradle/wrapper/gradle-wrapper.jar | cut -d' ' -f1)
test "$wrapper_hash" = "55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c" || {
    echo "unexpected Gradle wrapper JAR" >&2
    exit 1
}
grep -q '^distributionUrl=.*gradle-9\.4\.1-bin\.zip$' gradle/wrapper/gradle-wrapper.properties
grep -q '^distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb$' \
    gradle/wrapper/gradle-wrapper.properties

grep -q 'android:allowBackup="false"' app/android/src/main/AndroidManifest.xml
grep -q 'android:usesCleartextTraffic="false"' app/android/src/main/AndroidManifest.xml
grep -q 'android:networkSecurityConfig="@xml/network_security_config"' app/android/src/main/AndroidManifest.xml
grep -q '<base-config cleartextTrafficPermitted="false"' app/android/src/main/res/xml/network_security_config.xml
scripts/generate-sbom.py --check

echo "structure verified"
