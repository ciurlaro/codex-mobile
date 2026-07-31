#!/usr/bin/env python3
"""Generate the deterministic host CycloneDX SBOM from pinned build inputs."""

import argparse
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs" / "technical" / "sbom.cdx.json"
PROPERTIES = ROOT / "gradle.properties"
APP_LOCK = ROOT / "modules" / "android" / "app" / "gradle.lockfile"


def prop(name: str, pattern: str) -> str:
    for line in PROPERTIES.read_text().splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == name and re.fullmatch(pattern, value.strip()):
            return value.strip()
    raise SystemExit(f"missing or invalid SBOM source property: {name}")


def dependencies() -> list[tuple[str, str, str]]:
    values = set()
    for line in APP_LOCK.read_text().splitlines():
        if line.startswith("#") or "=" not in line:
            continue
        coordinate, configurations = line.split("=", 1)
        if "releaseRuntimeClasspath" in configurations.split(","):
            values.add(tuple(coordinate.split(":", 2)))
    return sorted(values)


def generate() -> str:
    version = prop("codexMobile.versionName", r".+")
    codex_version = prop("codexMobile.codexVersion", r"0\.145\.0")
    archive_hash = prop("codexMobile.codexArchiveSha256", r"[0-9a-f]{64}")
    binary_hash = prop("codexMobile.codexBinarySha256", r"[0-9a-f]{64}")
    app_ref = f"pkg:generic/codex-mobile@{version}?platform=android"
    internal = [
        {
            "type": "library",
            "bom-ref": f"pkg:generic/{name}@{version}",
            "name": name,
            "version": version,
            "purl": f"pkg:generic/{name}@{version}",
            "description": description,
            "licenses": [{"license": {"id": "GPL-3.0-or-later"}}],
        }
        for name, description in [
            (
                "codex-mobile-shared",
                "Portable App Server protocol, runtime policy, agent, application state, persistence, and UI.",
            ),
            (
                "codex-mobile-android-app",
                "Android process, socket, lifecycle, workspace, rendering, and packaging mechanisms.",
            ),
        ]
    ]
    codex_ref = f"pkg:generic/openai/codex-app-server@{codex_version}?arch=arm64"
    codex = {
        "type": "application",
        "bom-ref": codex_ref,
        "group": "OpenAI",
        "name": "codex-app-server",
        "version": codex_version,
        "purl": codex_ref,
        "description": "Pinned standalone Codex protocol runtime and ordinary-shell owner.",
        "hashes": [{"alg": "SHA-256", "content": binary_hash}],
        "licenses": [{"license": {"id": "Apache-2.0"}}],
        "properties": [
            {"name": "codex-mobile:archive-sha256", "value": archive_hash},
            {"name": "codex-mobile:source", "value": "github.com/openai/codex release"},
        ],
    }
    maven = []
    for group, name, dependency_version in dependencies():
        ref = f"pkg:maven/{group}/{name}@{dependency_version}"
        maven.append({
            "type": "library", "bom-ref": ref, "group": group, "name": name,
            "version": dependency_version, "purl": ref,
        })
    direct = internal + [codex] + maven
    refs = sorted(item["bom-ref"] for item in direct)
    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": f"urn:uuid:{uuid.uuid5(uuid.NAMESPACE_URL, app_ref)}",
        "version": 1,
        "metadata": {"component": {
            "type": "application", "bom-ref": app_ref, "name": "Codex Mobile",
            "version": version, "purl": app_ref,
            "description": "Independent Android Codex client with a portable shared runtime and UI.",
            "licenses": [{"license": {"id": "GPL-3.0-or-later"}}],
        }},
        "components": direct,
        "dependencies": [{"ref": app_ref, "dependsOn": refs}] + [
            {"ref": ref, "dependsOn": []} for ref in refs
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
            print("docs/technical/sbom.cdx.json is stale; run scripts/generate-sbom.py", file=sys.stderr)
            raise SystemExit(1)
    else:
        OUTPUT.write_text(generated)
        print(f"wrote {OUTPUT.relative_to(ROOT)} ({hashlib.sha256(generated.encode()).hexdigest()})")


if __name__ == "__main__":
    main()
