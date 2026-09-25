#!/usr/bin/env python3
"""Fetch the pinned runtime bundles a debug build needs.

The Gradle build (app/build.gradle.kts) wires `merge*Assets` tasks to
`prepareBundledAgentAssets` / `prepareOfflineRuntimeAssets`, which expect the
tarballs listed in `dist/runtime-bundles/manifest.json` to exist locally.
The tarballs are deliberately gitignored (400+ MB); upstream publishes them on
the GitHub release referenced by `runtimeReleaseBaseUrl` in app/build.gradle.kts.

Used by CI (.github/workflows/ci.yml). Downloads every bundle and verifies its
SHA-256 against the manifest before allowing the build to proceed. If a
checksum does not match, the file is deleted and the script exits non-zero so
the pipeline fails closed (an unverified runtime must never reach a build).
"""

import hashlib
import json
import sys
import urllib.request
from pathlib import Path

# Keep in sync with `runtimeReleaseBaseUrl` in app/build.gradle.kts.
RELEASE_BASE = "https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4"

BUNDLE_DIR = Path(__file__).resolve().parents[2] / "dist" / "runtime-bundles"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    manifest = json.loads((BUNDLE_DIR / "manifest.json").read_text())
    bundles = manifest.get("bundles", {})
    if not bundles:
        print("manifest.json lists no bundles; nothing to fetch", file=sys.stderr)
        return 1

    for name, info in bundles.items():
        target = BUNDLE_DIR / info["file"]
        expected = info["sha256"]
        if target.is_file() and sha256_of(target) == expected:
            print(f"[ok] {name}: {info['file']} already present and verified")
            continue
        if target.is_file():
            target.unlink()  # stale or corrupt — never build from it
        url = f"{RELEASE_BASE}/{info['file']}"
        print(f"[download] {name}: {url}")
        urllib.request.urlretrieve(url, target)
        actual = sha256_of(target)
        if actual != expected:
            print(
                f"checksum mismatch for {info['file']}: expected {expected}, got {actual}",
                file=sys.stderr,
            )
            target.unlink()
            return 1
        print(f"[verified] {name}: {info['file']} sha256 ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
