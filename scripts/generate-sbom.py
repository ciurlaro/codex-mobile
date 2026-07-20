#!/usr/bin/env python3
"""Generate the deterministic release CycloneDX SBOM from Gradle's lock state."""

import argparse
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs" / "sbom.cdx.json"
APP_BUILD = ROOT / "app" / "android" / "build.gradle.kts"
APP_LOCK = ROOT / "app" / "android" / "gradle.lockfile"


def required(pattern: str, text: str) -> str:
    match = re.search(pattern, text)
    if not match:
        raise SystemExit(f"missing SBOM source value: {pattern}")
    return match.group(1)


def component(group: str, name: str, version: str) -> dict:
    return {
        "type": "library",
        "bom-ref": f"pkg:maven/{group}/{name}@{version}",
        "group": group,
        "name": name,
        "version": version,
        "purl": f"pkg:maven/{group}/{name}@{version}",
    }


def generate() -> str:
    build = APP_BUILD.read_text()
    version = required(r'versionName = "([^"]+)"', build)
    codex_version = required(r'inputs\.property\("codexVersion", "([^"]+)"\)', build)
    archive_hash = required(r'inputs\.property\("archiveSha256", "([0-9a-f]{64})"\)', build)
    binary_hash = required(r'inputs\.property\("binarySha256", "([0-9a-f]{64})"\)', build)

    dependencies = set()
    for line in APP_LOCK.read_text().splitlines():
        if line.startswith("#") or "=" not in line:
            continue
        coordinate, configurations = line.split("=", 1)
        if "releaseRuntimeClasspath" not in configurations.split(","):
            continue
        group, name, dependency_version = coordinate.split(":", 2)
        dependencies.add((group, name, dependency_version))

    app_ref = f"pkg:generic/codex-mobile@{version}?platform=android"
    internal = [
        ("codex-mobile-core", "library"),
        ("codex-mobile-agent-codex", "library"),
        ("codex-mobile-platform-android", "library"),
    ]
    internal_components = [
        {
            "type": kind,
            "bom-ref": f"pkg:generic/{name}@{version}",
            "name": name,
            "version": version,
            "purl": f"pkg:generic/{name}@{version}",
        }
        for name, kind in internal
    ]
    codex_ref = f"pkg:generic/openai/codex-app-server@{codex_version}?arch=arm64"
    codex_component = {
        "type": "application",
        "bom-ref": codex_ref,
        "group": "OpenAI",
        "name": "codex-app-server",
        "version": codex_version,
        "purl": codex_ref,
        "hashes": [{"alg": "SHA-256", "content": binary_hash}],
        "licenses": [{"license": {"id": "Apache-2.0"}}],
        "properties": [
            {"name": "codex-mobile:archive-sha256", "value": archive_hash},
            {"name": "codex-mobile:source", "value": "github.com/openai/codex release"},
        ],
    }
    maven_components = [component(*coordinate) for coordinate in sorted(dependencies)]
    direct_refs = [item["bom-ref"] for item in internal_components + maven_components]
    direct_refs.append(codex_ref)
    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": f"urn:uuid:{uuid.uuid5(uuid.NAMESPACE_URL, app_ref)}",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "bom-ref": app_ref,
                "name": "Codex Mobile",
                "version": version,
                "purl": app_ref,
            }
        },
        "components": internal_components + [codex_component] + maven_components,
        "dependencies": [
            {"ref": app_ref, "dependsOn": sorted(direct_refs)},
            *({"ref": ref, "dependsOn": []} for ref in sorted(direct_refs)),
        ],
    }
    return json.dumps(bom, indent=2, sort_keys=True) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    generated = generate()
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text() != generated:
            print("docs/sbom.cdx.json is stale; run scripts/generate-sbom.py", file=sys.stderr)
            raise SystemExit(1)
        return
    OUTPUT.write_text(generated)
    print(f"wrote {OUTPUT.relative_to(ROOT)} ({hashlib.sha256(generated.encode()).hexdigest()})")


if __name__ == "__main__":
    main()
