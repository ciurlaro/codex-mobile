#!/usr/bin/env python3
"""Generate the deterministic release CycloneDX SBOM from Gradle's lock state."""

import argparse
import base64
import hashlib
import json
import re
import sys
import urllib.parse
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs" / "sbom.cdx.json"
APP_BUILD = ROOT / "app" / "android" / "build.gradle.kts"
APP_LOCK = ROOT / "app" / "android" / "gradle.lockfile"
NATIVE_BUILD = ROOT / "scripts" / "prepare-native-tools.sh"
TGCLI_LOCK = ROOT / "scripts" / "native" / "tgcli-package-lock.json"


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


def license_choice(value: str) -> dict:
    if value.startswith("LicenseRef-"):
        return {"license": {"name": value.removeprefix("LicenseRef-")}}
    if re.search(r"\b(?:AND|OR|WITH)\b", value):
        return {"expression": value}
    return {"license": {"id": value}}


def native_component(
    name: str,
    version: str,
    license_id: str,
    input_hash: str,
    source: str,
    kind: str = "library",
) -> dict:
    ref = f"pkg:generic/{name}@{version}?arch=arm64"
    return {
        "type": kind,
        "bom-ref": ref,
        "name": name,
        "version": version,
        "purl": ref,
        "licenses": [license_choice(license_id)],
        "properties": [
            {"name": "codex-mobile:input-sha256", "value": input_hash},
            {"name": "codex-mobile:source", "value": source},
        ],
    }


def npm_components() -> list[dict]:
    packages = json.loads(TGCLI_LOCK.read_text())["packages"]
    components = {}
    for path, package in packages.items():
        if not path or package.get("dev") is True or "node_modules/" not in path:
            continue
        name = path.rsplit("node_modules/", 1)[1]
        version = package.get("version")
        if not version:
            continue
        escaped = urllib.parse.quote(name, safe="/")
        ref = f"pkg:npm/{escaped}@{version}"
        item = {
            "type": "library",
            "bom-ref": ref,
            "name": name,
            "version": version,
            "purl": ref,
        }
        license_id = package.get("license")
        if license_id:
            item["licenses"] = [license_choice(license_id)]
        integrity = package.get("integrity", "")
        if integrity.startswith("sha512-"):
            digest = base64.b64decode(integrity.removeprefix("sha512-")).hex()
            item["hashes"] = [{"alg": "SHA-512", "content": digest}]
        components[ref] = item
    return [components[key] for key in sorted(components)]


def generate() -> str:
    build = APP_BUILD.read_text()
    native_build = NATIVE_BUILD.read_text()
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
    native_specs = [
        ("mupdf", "1.28.0", "AGPL-3.0-only", "21c7f064903154f1c3a7458bee81f130fc36f9b5147ea13328f9980e02d2dea2", "mupdf.com source archive", "application"),
        ("tesseract", "5.5.2", "Apache-2.0", "21c7f064903154f1c3a7458bee81f130fc36f9b5147ea13328f9980e02d2dea2", "MuPDF 1.28.0 thirdparty source", "application"),
        ("leptonica", "1.87.0", "LicenseRef-Leptonica", "21c7f064903154f1c3a7458bee81f130fc36f9b5147ea13328f9980e02d2dea2", "MuPDF 1.28.0 thirdparty source", "library"),
        ("tessdata-fast-eng", "4.1.0", "Apache-2.0", "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2", "github.com/tesseract-ocr/tessdata_fast", "data"),
        ("officecli", "1.0.139", "Apache-2.0", "c59a6989cd8bb342a421d43a8ac0d01d56eee59631be3238c426d082b4c8c07c", "github.com/iOfficeAI/OfficeCLI release", "application"),
        ("musl", "1.2.5-r23", "MIT", "6a3edd924ead1fad88a69e28c5775809af3026b322f58428001cd02fedc5299e", "Alpine 3.23 package", "library"),
        ("gcc-runtime", "15.2.0-r2", "GPL-3.0-with-GCC-exception", "eaaafda78fde1c904e1741680ddea91649f051e29a343152c8a4327605704b0f", "Alpine 3.23 libgcc package", "library"),
        ("libstdc++", "15.2.0-r2", "GPL-3.0-with-GCC-exception", "10d72e25f6fcc0f3d9fdd801c9bdaed81d6e836aa2b65b63f25d2d97f860a7d1", "Alpine 3.23 package", "library"),
        ("tgcli", "2.1.0+649d937", "MIT", "f1be9cd6b4b9170da4fc64be6b95377be6bee054bc49731d8bcb39dfdbcd94ed", "github.com/kfastov/tgcli source archive", "application"),
        ("nodejs-lts", "24.17.0", "MIT", "391428ee751dd1e960c8d3fbe02f7c2c18bb2b20a226d55ac920364e0bb51604", "Termux package", "application"),
        ("libc++", "29", "Apache-2.0", "bb9f12113c137aa0e8513bb51cc49fe77a5ce3ca39ab9e92c57d228ecdf00222", "Termux package", "library"),
        ("openssl", "3.6.3", "Apache-2.0", "86760e9ce736f463236f2c15b1eb3a3fdcfc5778d0fd7077a917448dcc90f3aa", "Termux package", "library"),
        ("c-ares", "1.34.8", "MIT", "7681fc23e822d7988ba8b2adf3468f93ae68f724dda365cff1385096a9fa87e6", "Termux package", "library"),
        ("icu", "78.3", "ICU", "f536403f65a08fe0df6e7304184e902d54def77d5c3bd5edfd9109d57601d276", "Termux package", "library"),
        ("sqlite", "3.53.3", "blessing", "147365c5633b571bea063ab6c27022577fca89d73e99a7607030602b0166eded", "Termux package", "library"),
        ("zlib", "1.3.2", "Zlib", "75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9", "Termux package", "library"),
    ]
    for spec in native_specs:
        if spec[3] not in native_build:
            raise SystemExit(f"native SBOM input is no longer pinned by build: {spec[0]}")
    native_components = [native_component(*spec) for spec in native_specs]
    npm = npm_components()
    maven_components = [component(*coordinate) for coordinate in sorted(dependencies)]
    direct_refs = [item["bom-ref"] for item in internal_components + maven_components + native_components]
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
        "components": internal_components + [codex_component] + native_components + npm + maven_components,
        "dependencies": [
            {"ref": app_ref, "dependsOn": sorted(direct_refs)},
            *({"ref": ref, "dependsOn": []} for ref in sorted(direct_refs)),
            *({"ref": item["bom-ref"], "dependsOn": []} for item in npm),
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
