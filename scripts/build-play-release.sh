#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
keystore_path="${MH_UPLOAD_STORE_FILE:-${HOME}/.mobile-harness/mobile-harness-upload.jks}"
key_alias="${MH_UPLOAD_KEY_ALIAS:-mobile-harness-upload}"
keychain_account="com.jarves.mh"
keychain_service="Mobile Harness Upload Key"
version_code="${1:-1}"
version_name="${2:-1.0.0}"

if [[ ! -f "$keystore_path" ]]; then
  echo "Upload keystore not found: $keystore_path (set MH_UPLOAD_STORE_FILE to specify path)" >&2
  exit 1
fi

upload_store_password="${MH_UPLOAD_STORE_PASSWORD:-}"
upload_key_password="${MH_UPLOAD_KEY_PASSWORD:-}"
if [[ -z "$upload_store_password" ]] && command -v security >/dev/null 2>&1; then
  upload_store_password="$(security find-generic-password -w -a "$keychain_account" -s "$keychain_service" 2>/dev/null || true)"
fi
if [[ -z "$upload_key_password" ]]; then
  upload_key_password="$upload_store_password"
fi

if [[ -z "$upload_store_password" ]]; then
  echo "Upload secret not found: set MH_UPLOAD_STORE_PASSWORD or add to macOS keychain ($keychain_service)" >&2
  exit 1
fi
trap 'unset upload_store_password upload_key_password' EXIT

cd "$project_dir"
MH_UPLOAD_STORE_FILE="$keystore_path" \
MH_UPLOAD_STORE_PASSWORD="$upload_store_password" \
MH_UPLOAD_KEY_ALIAS="$key_alias" \
MH_UPLOAD_KEY_PASSWORD="$upload_key_password" \
./gradlew \
  -PplayBuild=true \
  -PappVersionCode="$version_code" \
  -PappVersionName="$version_name" \
  playReadinessCheck testOnlineDebugUnitTest lintOnlineDebug assembleRelease bundleRelease

echo "Signed online bundle: $project_dir/app/build/outputs/bundle/onlineRelease/app-online-release.aab"
echo "Signed offline bundle: $project_dir/app/build/outputs/bundle/offlineRelease/app-offline-release.aab"
echo "Signed online APK: $project_dir/app/build/outputs/apk/online/release/app-online-release.apk"
echo "Signed offline APK: $project_dir/app/build/outputs/apk/offline/release/app-offline-release.apk"
