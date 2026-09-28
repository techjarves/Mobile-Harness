#!/usr/bin/env bash
#
# Builds a newer debug APK and serves it (with a matching
# mobile-harness-update.json) from a local HTTP directory so the in-app
# updater can be exercised end-to-end without publishing to GitHub.
#
# Usage:
#   ./scripts/serve-update-server.sh PORT BASE_URL [VERSION_CODE] [VERSION_NAME]
#
# Examples:
#   ./scripts/serve-update-server.sh 8080 https://example.trycloudflare.com 4 1.0.3-test
#
# Paste BASE_URL + /mobile-harness-update.json into the debug app's
# Settings → Update channel and tap Use & check.

set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
port="${1:-8080}"
base_url="${2:-}"
version_code="${3:-4}"
version_name="${4:-1.0.3-test}"

if [[ "$base_url" != https://* ]]; then
  echo "BASE_URL must be the HTTPS URL from Cloudflare Tunnel or ngrok." >&2
  echo "Usage: $0 PORT https://your-tunnel.example [VERSION_CODE] [VERSION_NAME]" >&2
  exit 1
fi
serve_dir="$project_dir/dist/update-test"
flavor="online"
variant="debug"
apk_name="mobile-harness-${flavor}-${variant}.apk"

cd "$project_dir"

echo "==> Preparing $serve_dir"
rm -rf "$serve_dir"
mkdir -p "$serve_dir"

echo "==> Building debug APK with versionCode $version_code, versionName $version_name"
./gradlew ":app:assembleOnlineDebug" \
    -PappVersionCode="$version_code" \
    -PappVersionName="$version_name" \
    --quiet

apk_src="$project_dir/app/build/outputs/apk/$flavor/$variant/app-${flavor}-${variant}.apk"
if [[ ! -f "$apk_src" ]]; then
    echo "Expected APK not found: $apk_src" >&2
    exit 1
fi

cp "$apk_src" "$serve_dir/$apk_name"
sha="$(shasum -a 256 "$serve_dir/$apk_name" | awk '{print $1}')"
size="$(stat -f%z "$serve_dir/$apk_name")"

cat > "$serve_dir/mobile-harness-update.json" <<JSON
{
  "versionCode": $version_code,
  "versionName": "$version_name",
  "notes": "Local test build served from $serve_dir.",
  "artifacts": {
    "$flavor": {
      "url": "$base_url/$apk_name",
      "sha256": "$sha",
      "sizeBytes": $size
    }
  }
}
JSON

cat > "$serve_dir/README.txt" <<TXT
Local update test server.

Files:
  - mobile-harness-update.json
  - $apk_name  (sha256: $sha)

APK URL baked into the manifest:
  $base_url/$apk_name

In a debug build of Mobile Harness, open Settings → Update channel, paste:
  $base_url/mobile-harness-update.json
and tap Use & check.

The app accepts this URL because the tunnel provides HTTPS. No LAN IP or
cleartext network-security exception is required.
TXT

echo "==> Manifest ready at $base_url/mobile-harness-update.json"
echo "    APK at $base_url/$apk_name"
echo "    Files:"
ls -lh "$serve_dir"
echo
echo "==> Starting HTTP server on port $port (Ctrl-C to stop)"

cd "$serve_dir"
exec python3 -m http.server "$port"
