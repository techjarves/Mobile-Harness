#!/usr/bin/env bash
set -euo pipefail

# Build a PocketDev overlay from Cursor's checksum-pinned official linux/arm64 CLI.
# The archive contains the official tree, a launcher symlink, and a version marker.
# It does not contain API keys, auth state, projects, or chats.
VERSION="${POCKETDEV_CURSOR_VERSION:-2026.10.19-6bbf2c4}"
BUNDLE_VERSION="${POCKETDEV_CURSOR_BUNDLE_VERSION:-2026.10.1}"
SOURCE_URL="${POCKETDEV_CURSOR_URL:-https://downloads.cursor.com/lab/2026.10.19-6bbf2c4/linux/arm64/agent-cli-package.tar.gz}"
SOURCE_SHA256="${POCKETDEV_CURSOR_SHA256:-71a90f12a8b8183a71442b7ed4037bddc1a0f88114ec4fb9ec92782acb8e7d84}"
ARCHIVE="pocketdev-cursor-arm64-${BUNDLE_VERSION}.tar.zst"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v zstd >/dev/null || { echo "zstd is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
mkdir -p "$temp_dir/src" "$ROOT_DIR/dist/runtime-bundles"

curl -fL --retry 3 -o "$temp_dir/cursor.tar.gz" "$SOURCE_URL"
actual_sha256="$(shasum -a 256 "$temp_dir/cursor.tar.gz" | cut -d' ' -f1)"
[[ "$actual_sha256" == "$SOURCE_SHA256" ]] || {
  echo "Cursor source checksum mismatch" >&2
  echo "expected $SOURCE_SHA256" >&2
  echo "actual   $actual_sha256" >&2
  exit 1
}

tar -xzf "$temp_dir/cursor.tar.gz" -C "$temp_dir/src"
launcher="$(find "$temp_dir/src" -type f -name cursor-agent | head -n 1)"
[[ -n "$launcher" ]] || { echo "Official archive has no cursor-agent launcher" >&2; exit 1; }
src_dir="$(dirname "$launcher")"
[[ -x "$src_dir/node" || -f "$src_dir/node" ]] || { echo "Official archive separated cursor-agent from node" >&2; exit 1; }
[[ -f "$src_dir/index.js" ]] || { echo "Official archive has no index.js next to cursor-agent" >&2; exit 1; }

dest="$temp_dir/payload/root/.local/share/cursor-agent/versions/$VERSION"
mkdir -p "$dest" "$temp_dir/payload/usr/local/bin" "$temp_dir/payload/root/.cursor"
cp -a "$src_dir"/. "$dest"/
chmod 755 "$dest/cursor-agent" "$dest/node" || true
ln -s "/root/.local/share/cursor-agent/versions/$VERSION/cursor-agent" "$temp_dir/payload/usr/local/bin/cursor-agent"
mkdir -p "$temp_dir/payload/root"
printf '%s\n' "$VERSION" > "$temp_dir/payload/root/.pocket-cursor-version"
cat > "$temp_dir/payload/root/.cursor/cli-config.json" <<'EOF'
{
  "version": 1,
  "editor": { "vimMode": false },
  "permissions": {
    "allow": ["Shell(*)", "Read(**)", "Write(**)", "WebFetch(*)"],
    "deny": []
  },
  "approvalMode": "unrestricted"
}
EOF

python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$temp_dir/payload" "$temp_dir/payload.tar"
zstd -19 -T0 -f "$temp_dir/payload.tar" -o "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE"
echo "sha256 $(shasum -a 256 "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE")"
wc -c "$ROOT_DIR/dist/runtime-bundles/$ARCHIVE" "$temp_dir/payload.tar"
echo "Cursor bundle created: dist/runtime-bundles/$ARCHIVE"
