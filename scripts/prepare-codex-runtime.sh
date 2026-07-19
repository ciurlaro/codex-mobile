#!/usr/bin/env bash
set -euo pipefail

version=${1:?Codex version is required}
archive_sha256=${2:?Archive SHA-256 is required}
binary_sha256=${3:?Binary SHA-256 is required}
output=${4:?Output path is required}
asset="codex-app-server-aarch64-unknown-linux-musl"
url="https://github.com/openai/codex/releases/download/rust-v${version}/${asset}.tar.gz"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error "$url" -o "$work/runtime.tar.gz"
printf '%s  %s\n' "$archive_sha256" "$work/runtime.tar.gz" | shasum -a 256 -c -
tar -xzf "$work/runtime.tar.gz" -C "$work" "$asset"
printf '%s  %s\n' "$binary_sha256" "$work/$asset" | shasum -a 256 -c -
mkdir -p "$(dirname "$output")"
install -m 755 "$work/$asset" "$output"
