#!/usr/bin/env bash
set -euo pipefail

# Download and verify required runtime bundle(s) for Mobile Harness build.
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
bundle_dir="$project_dir/dist/runtime-bundles"
mkdir -p "$bundle_dir"

bundle_file="pocketdev-agy-arm64-2026.09.1.tar.zst"
target="$bundle_dir/$bundle_file"
expected_sha="a659ab9188956fc4721ca86fb21b5118e0e489f47a5e02ae6b4f2fb423659d78"
url="https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4/$bundle_file"

compute_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        python3 -c "import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], 'rb').read()).hexdigest())" "$1"
    fi
}

if [[ -f "$target" ]]; then
    actual_sha="$(compute_sha256 "$target")"
    if [[ "$actual_sha" == "$expected_sha" ]]; then
        echo "==> Runtime bundle $bundle_file already present and verified."
        exit 0
    else
        echo "==> Checksum mismatch for existing $bundle_file ($actual_sha vs $expected_sha). Re-downloading..."
    fi
fi

echo "==> Downloading $bundle_file from $url..."
curl -fsSL --retry 3 --retry-delay 2 "$url" -o "$target"

actual_sha="$(compute_sha256 "$target")"
if [[ "$actual_sha" != "$expected_sha" ]]; then
    echo "ERROR: SHA256 mismatch for $bundle_file!" >&2
    echo "Expected: $expected_sha" >&2
    echo "Actual:   $actual_sha" >&2
    rm -f "$target"
    exit 1
fi

echo "==> Runtime bundle verified successfully (SHA256: $actual_sha)."
