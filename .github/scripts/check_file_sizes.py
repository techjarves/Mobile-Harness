#!/usr/bin/env python3
"""File-size guard (ISSUE-014, roadmap track 1): no Kotlin source file may
exceed MAX_LINES unless it is on the legacy allowlist below. New code must be
split; the three historical god classes are being decomposed PR-by-PR and stay
allowlisted until they are gone.

Usage: python3 .github/scripts/check_file_sizes.py [repo_root]
Exits non-zero with a report when a non-allowlisted file is too large.
"""

import sys
from pathlib import Path

MAX_LINES = 1500

# Historical files that already exceeded the cap when the rule was introduced.
# Entries are removed as the decomposition track shrinks them below the cap.
LEGACY_ALLOWLIST = {
    "app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt",
    "app/src/main/java/com/jarves/mh/ui/MainViewModel.kt",
    "app/src/main/java/com/jarves/mh/ui/AgentScreen.kt",
    "app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt",
}


def main() -> int:
    repo_root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    violations = []
    for path in sorted((repo_root / "app" / "src").rglob("*.kt")):
        relative = path.relative_to(repo_root).as_posix()
        if relative in LEGACY_ALLOWLIST:
            continue
        line_count = sum(1 for _ in path.open(encoding="utf-8"))
        if line_count > MAX_LINES:
            violations.append((relative, line_count))

    if violations:
        print("File-size guard failed (ISSUE-014): non-legacy Kotlin files over %d lines:" % MAX_LINES)
        for relative, count in violations:
            print(f"  {relative}: {count} lines")
        print("Split the file into focused units, or move existing content out first.")
        return 1
    print("File-size guard passed: every non-legacy Kotlin file is under %d lines." % MAX_LINES)
    return 0


if __name__ == "__main__":
    sys.exit(main())
