#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
keystore_path="${MH_UPLOAD_STORE_FILE:-/Users/jarves/.mobile-harness/mobile-harness-upload.jks}"
key_alias="${MH_UPLOAD_KEY_ALIAS:-mobile-harness-upload}"
keychain_account="com.jarves.mh"
keychain_service="Mobile Harness Upload Key"
version_code="${1:-1}"
version_name="${2:-1.0.0}"

if [[ ! -f "$keystore_path" ]]; then
  echo "Upload keystore not found: $keystore_path" >&2
  exit 1
fi

upload_secret="$(security find-generic-password -w -a "$keychain_account" -s "$keychain_service")"
trap 'unset upload_secret' EXIT

cd "$project_dir"
MH_UPLOAD_STORE_FILE="$keystore_path" \
MH_UPLOAD_STORE_PASSWORD="$upload_secret" \
MH_UPLOAD_KEY_ALIAS="$key_alias" \
MH_UPLOAD_KEY_PASSWORD="$upload_secret" \
./gradlew \
  -PplayBuild=true \
  -PappVersionCode="$version_code" \
  -PappVersionName="$version_name" \
  playReadinessCheck testDebugUnitTest lintDebug assembleRelease bundleRelease

echo "Signed bundle: $project_dir/app/build/outputs/bundle/release/app-release.aab"
echo "Signed APK: $project_dir/app/build/outputs/apk/release/app-release.apk"
