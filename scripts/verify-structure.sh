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
    app/android/src/main/assets/private-backends-NOTICE.txt
    agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/CodexRuntime.kt
    agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/BuiltInTools.kt
    platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidCodexRuntime.kt
    platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidBuiltInToolDispatcher.kt
    platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/BuiltInMutationJournal.kt
    platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/BuiltInPluginBundle.kt
    platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/PrivateBackendBundle.kt
    platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/TelegramIntegration.kt
    platform/android/src/main/assets/codex/plugins/codex-mobile/marketplace.json
    platform/android/src/main/assets/codex/plugins/codex-mobile/plugins/documents/.codex-plugin/plugin.json
    platform/android/src/main/assets/codex/plugins/codex-mobile/plugins/documents/skills/documents/SKILL.md
    platform/android/src/main/assets/codex/plugins/codex-mobile/plugins/telegram/.codex-plugin/plugin.json
    platform/android/src/main/assets/codex/plugins/codex-mobile/plugins/telegram/skills/telegram/SKILL.md
    scripts/generate-sbom.py
    scripts/install-phone.sh
    scripts/native/officecli-launcher.c
    scripts/native/tgcli-launcher.c
    scripts/native/tgcli-package-lock.json
    scripts/patches/tesseract-android.patch
    scripts/patches/tgcli-android.patch
    scripts/prepare-private-backends.sh
    scripts/release-local.sh
    scripts/verify-release.sh
    scripts/verify-reproducible-release.sh
)

for path in "${required[@]}"; do
    test -f "$path" || { echo "missing required file: $path" >&2; exit 1; }
done

grep -qx 'codexMobile.codexVersion=0.144.6' gradle.properties || {
    echo "Codex app-server must remain pinned at 0.144.6" >&2
    exit 1
}

if grep -R -n -E 'ProcessBuilder|java\.lang\.Process|java\.io\.(InputStream|OutputStream)' \
    agent/codex/src/main; then
    echo "app-server process mechanics must stay behind AndroidCodexRuntime" >&2
    exit 1
fi

if grep -n -E 'mutool|tesseract|officecli|tgcli|private-backends' \
    platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidCodexRuntime.kt; then
    echo "private backends must be absent from the app-server environment" >&2
    exit 1
fi
grep -q 'environment()\["PATH"\] = "/system/bin:/system/xbin"' \
    platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/PrivateBackendBundle.kt

for tool in \
    documents_read documents_view_pages documents_edit \
    telegram_list_chats telegram_list_messages telegram_search_messages \
    telegram_search_contacts telegram_download_media telegram_send_text telegram_send_file; do
    grep -q "\"$tool\"" \
        agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/BuiltInTools.kt
done

if grep -n -E '"(command|subcommand|argv|rawArguments)"' \
    agent/codex/src/main/kotlin/io/github/ciurlaro/codexmobile/agent/codex/BuiltInTools.kt; then
    echo "built-in dynamic tools must not expose a generic native command" >&2
    exit 1
fi

grep -q '"--retries", "0"' \
    platform/android/src/main/kotlin/io/github/ciurlaro/codexmobile/platform/android/AndroidBuiltInToolDispatcher.kt

for json in \
    platform/android/src/main/assets/codex/plugins/codex-mobile/marketplace.json \
    platform/android/src/main/assets/codex/plugins/codex-mobile/plugins/documents/.codex-plugin/plugin.json \
    platform/android/src/main/assets/codex/plugins/codex-mobile/plugins/telegram/.codex-plugin/plugin.json; do
    python3 -m json.tool "$json" >/dev/null
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

if grep -R -n -E --include='*.kt' '@Ignore|TODO[[:space:]]*\(' \
    core/src agent/codex/src platform/android/src app/android/src; then
    echo "tests must not be ignored or left as TODO" >&2
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
