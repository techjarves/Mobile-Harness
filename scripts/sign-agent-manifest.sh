#!/usr/bin/env bash
# Signs the agent update manifest for the project-controlled update feed
# (roadmap 3k, ISSUE-002 long-term fix).
#
# Usage:
#   ./scripts/sign-agent-manifest.sh <manifest.json> <path/to/agent-updates.key>
#
# Requires the real minisign tool: https://jedisct1.github.io/minisign/
# The secret key must live on the OFFLINE signing machine only — never in
# this repository, never in CI. Full runbook:
# docs/release/signing-agent-updates.md
set -euo pipefail

MANIFEST="${1:-}"
KEY="${2:-}"

die() { echo "error: $*" >&2; exit 1; }

[ -n "$MANIFEST" ] && [ -n "$KEY" ] || die "usage: $0 <manifest.json> <agent-updates.key>"
[ -f "$MANIFEST" ] || die "manifest not found: $MANIFEST"
[ -f "$KEY" ] || die "minisign secret key not found: $KEY"
command -v minisign >/dev/null 2>&1 || die "minisign is required (https://jedisct1.github.io/minisign/)"

# Sanity gate before signing: schema 1, https-only URLs, 128-hex digests.
python3 - "$MANIFEST" <<'PY'
import json, re, sys
with open(sys.argv[1], "rb") as fh:
    raw = fh.read()
manifest = json.loads(raw.decode("utf-8"))
assert manifest.get("schema") == 1, "schema must be 1"
agents = manifest.get("agents") or {}
assert agents, "agents object must not be empty"
for name, entry in agents.items():
    assert re.fullmatch(r"[0-9A-Za-z.+-]{1,64}", entry.get("version", "")), f"{name}: bad version"
    assert entry.get("url", "").startswith("https://"), f"{name}: url must be https"
    assert re.fullmatch(r"[0-9a-fA-F]{128}", entry.get("sha512", "")), f"{name}: sha512 must be 128 hex chars"
print(f"[ok] manifest sanity: schema=1, {len(agents)} agent entries, {len(raw)} bytes")
PY

SIG="$MANIFEST.minisig"
COMMENT="agent-updates $(date -u +%Y-%m-%d)"

minisign -Sm "$MANIFEST" -s "$KEY" -x "$SIG" -t "$COMMENT"

# The signature must verify against the public key next to the secret one.
PUB="${KEY%.key}.pub"
[ -f "$PUB" ] && minisign -Vm "$MANIFEST" -p "$PUB" -x "$SIG"

cat <<EOF

signed: $SIG
trusted comment: $COMMENT

publish (the agent-updates branch IS the feed the app polls):
  git checkout agent-updates
  cp "$MANIFEST" agent-updates-manifest.json
  cp "$SIG" agent-updates-manifest.json.minisig
  git add agent-updates-manifest.json agent-updates-manifest.json.minisig
  git commit -m "agent-updates: $COMMENT"
  git push origin agent-updates
EOF
