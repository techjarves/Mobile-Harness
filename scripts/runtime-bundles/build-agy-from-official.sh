#!/usr/bin/env bash
set -euo pipefail

# Build a portable PocketDev overlay from Google's checksum-pinned official
# Antigravity CLI release. The archive contains only the executable and version
# marker: no OAuth credentials, settings, conversations, projects, or device data.
VERSION="${POCKETDEV_AGY_VERSION:-1.1.27}"
BUNDLE_VERSION="${POCKETDEV_AGY_BUNDLE_VERSION:-2026.09.1}"
SOURCE_URL="${POCKETDEV_AGY_URL:-https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.27-5211191891591168/linux-arm/cli_linux_arm64.tar.gz}"
SOURCE_SHA512="${POCKETDEV_AGY_SHA512:-ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9}"
ARCHIVE="pocketdev-agy-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
mkdir -p "$temp_dir/payload/root/.local/bin" "$ROOT_DIR/dist/runtime-bundles"

curl -fL --retry 3 -o "$temp_dir/agy.tar.gz" "$SOURCE_URL"
actual_sha512="$(shasum -a 512 "$temp_dir/agy.tar.gz" | cut -d' ' -f1)"
[[ "$actual_sha512" == "$SOURCE_SHA512" ]] || {
  echo "Antigravity source checksum mismatch" >&2
  exit 1
}

tar -xzf "$temp_dir/agy.tar.gz" -C "$temp_dir" antigravity
install -m 0755 "$temp_dir/antigravity" "$temp_dir/payload/root/.local/bin/agy"
printf '%s\n' "$VERSION" > "$temp_dir/payload/.pocket-agy-version"

python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
shasum -a 256 "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
wc -c "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE" "$temp_dir/payload.tar"
echo "Antigravity bundle created: dist/runtime-bundles/$ARCHIVE"
