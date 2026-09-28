#!/usr/bin/env bash
set -euo pipefail

# Package the pinned DeepSeek Harness installation from PocketDev's private
# Ubuntu runtime. Only the npm payload, launcher symlink, and version marker are
# exported; provider settings, API keys, sessions, chats, and workspaces are not.
ADB_SERIAL="${ADB_SERIAL:-}"
PACKAGE="${POCKETDEV_PACKAGE:-com.jarves.mh}"
VERSION="${POCKETDEV_DSH_BUNDLE_VERSION:-2026.09.1}"
DSH_VERSION="${POCKETDEV_DSH_VERSION:-0.1.2-rc.1}"
ARCHIVE="pocketdev-dsh-arm64-${VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi
}

command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }
adb_cmd wait-for-device
adb_cmd shell run-as "$PACKAGE" test -f files/runtime/ubuntu/usr/local/lib/dsh/node_modules/.bin/dsh
actual_version="$(adb_cmd exec-out run-as "$PACKAGE" cat files/runtime/ubuntu/.pocket-dsh-version | tr -d '\r\n')"
[[ "$actual_version" == "$DSH_VERSION" ]] || {
  echo "Expected DeepSeek Harness $DSH_VERSION, found $actual_version" >&2
  exit 1
}

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"; adb_cmd shell run-as "$PACKAGE" rm -f cache/pocketdev-dsh-export.tar >/dev/null 2>&1 || true' EXIT
mkdir -p "$temp_dir/payload" dist/runtime-bundles

adb_cmd shell run-as "$PACKAGE" tar -cf cache/pocketdev-dsh-export.tar \
  -C files/runtime/ubuntu usr/local/lib/dsh usr/local/bin/dsh .pocket-dsh-version
adb_cmd exec-out run-as "$PACKAGE" cat cache/pocketdev-dsh-export.tar > "$temp_dir/source.tar"
tar -xf "$temp_dir/source.tar" -C "$temp_dir/payload"

[[ "$(cat "$temp_dir/payload/.pocket-dsh-version")" == "$DSH_VERSION" ]]
[[ -L "$temp_dir/payload/usr/local/bin/dsh" ]]
[[ -f "$temp_dir/payload/usr/local/lib/dsh/node_modules/.bin/dsh" ]]
python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "dist/runtime-bundles/$ARCHIVE"

shasum -a 256 "dist/runtime-bundles/$ARCHIVE"
wc -c "dist/runtime-bundles/$ARCHIVE" "$temp_dir/payload.tar"
echo "DeepSeek Harness bundle created: dist/runtime-bundles/$ARCHIVE"
