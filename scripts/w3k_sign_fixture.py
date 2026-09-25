#!/usr/bin/env python3
"""3k fixture generator: real Ed25519 keypairs + minisign-format signatures
over realistic agent-update manifests, for the JVM test suite.

Pure Python (RFC 8032 reference algorithm, no third-party deps). The Kotlin
verifier accepting these fixtures proves cross-implementation compatibility;
the RFC 8032 test vectors inside Ed25519Test prove both sides implement
Ed25519 correctly in the first place.

Output: app/src/test/java/com/jarves/mh/runtime/TestFixtures3k.kt
(GENERATED file - committed so tests are hermetic; regenerate with this script.)
"""
import base64
import hashlib
import json
from pathlib import Path

P = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = (-121665 * pow(121666, P - 2, P)) % P
I = pow(2, (P - 1) // 4, P)
G_Y = (4 * pow(5, P - 2, P)) % P
IDENTITY = (0, 1, 1, 0)


def recover_x(y, sign):
    y2 = (y * y) % P
    u = (y2 - 1) % P
    v = (D * y2 + 1) % P
    x = (u * pow(v, 3, P) * pow(u * pow(v, 7, P), (P - 5) // 8, P)) % P
    if (v * x * x) % P == u:
        pass
    elif (v * x * x) % P == (-u) % P:
        x = (x * I) % P
    else:
        return None
    if x == 0 and sign:
        return None
    if (x & 1) != sign:
        x = P - x
    return x


def _recover_base():
    x = recover_x(G_Y, 0)
    assert x is not None, "base point recovery failed"
    return (x, G_Y, 1, (x * G_Y) % P)


B = _recover_base()


def add(p, q):
    x1, y1, z1, t1 = p
    x2, y2, z2, t2 = q
    a = ((y1 - x1) * (y2 - x2)) % P
    b = ((y1 + x1) * (y2 + x2)) % P
    c = (t1 * 2 * D * t2) % P
    d = (z1 * 2 * z2) % P
    e = (b - a) % P
    f = (d - c) % P
    g = (d + c) % P
    h = (b + a) % P
    return ((e * f) % P, (g * h) % P, (f * g) % P, (e * h) % P)


def scalarmult(k, p):
    result = IDENTITY
    addend = p
    while k > 0:
        if k & 1:
            result = add(result, addend)
        addend = add(addend, addend)
        k >>= 1
    return result


def point_to_bytes(point):
    x, y, z, _ = point
    zinv = pow(z, P - 2, P)
    x = (x * zinv) % P
    y = (y * zinv) % P
    return ((y | ((x & 1) << 255))).to_bytes(32, "little")


def secret_expand(seed):
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= (1 << 254)
    return a, h[32:]


def public_from_seed(seed):
    a, _ = secret_expand(seed)
    return point_to_bytes(scalarmult(a, B))


def sign(seed, message):
    """RFC 8032 deterministic signature: returns (public_key, sig64)."""
    a, prefix = secret_expand(seed)
    public = point_to_bytes(scalarmult(a, B))
    r = int.from_bytes(hashlib.sha512(prefix + message).digest(), "little") % L
    r_bytes = point_to_bytes(scalarmult(r, B))
    h = int.from_bytes(hashlib.sha512(r_bytes + public + message).digest(), "little") % L
    s = (r + h * a) % L
    return public, r_bytes + s.to_bytes(32, "little")


def key_id(public):
    """Test convention only: the verifier compares ids for equality; genuine
    minisign uses BLAKE2b-64(public) and embeds the same id on both sides."""
    return hashlib.sha512(public).digest()[:8]


def b64(raw):
    return base64.b64encode(raw).decode("ascii")


def key_entry(public):
    return b64(key_id(public) + public)


def minisig_file(public, sig64, trusted_comment, with_comment=True):
    blob = b"Ed" + key_id(public) + sig64
    lines = ["untrusted comment: fixture", b64(blob)]
    if with_comment:
        global_sig = sign(FIXTURE_SEED_A, trusted_comment.encode() + sig64)[1] \
            if public == PUBLIC_A else sign(FIXTURE_SEED_B, trusted_comment.encode() + sig64)[1]
        lines += ["trusted comment: " + trusted_comment, b64(global_sig)]
    return ("\n".join(lines) + "\n").encode()


FIXTURE_SEED_A = b"mobile-harness-3k-fixture-key-A"[:32].ljust(32, b"\x00")
FIXTURE_SEED_B = b"mobile-harness-3k-fixture-key-B"[:32].ljust(32, b"\x00")
PUBLIC_A = public_from_seed(FIXTURE_SEED_A)
PUBLIC_B = public_from_seed(FIXTURE_SEED_B)

SHA = lambda n: ("ab" * 64)[:128] if n == 128 else ("cd" * 64)[:n]

MANIFESTS = {
    "VALID": {
        "schema": 1,
        "generatedAt": "2026-09-25",
        "agents": {
            "claude-code": {
                "version": "2.1.264",
                "url": "https://downloads.claude.ai/claude-code-releases/2.1.264/linux-arm64/claude",
                "sha512": SHA(128),
            },
            "deepseek-harness": {
                "version": "0.1.3-rc.1",
                "url": "https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-0.1.3-rc.1.tgz",
                "sha512": SHA(128),
            },
            "antigravity": {
                "version": "1.1.28",
                "url": "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.28/linux-arm/cli_linux_arm64.tar.gz",
                "sha512": SHA(128),
            },
        },
    },
    # Correctly signed but policy-invalid: http URL must be rejected.
    "HTTP_URL": {
        "schema": 1,
        "agents": {
            "antigravity": {
                "version": "1.1.29",
                "url": "http://evil.example.com/cli.tar.gz",
                "sha512": SHA(128),
            },
        },
    },
    # Correctly signed but policy-invalid: truncated digest.
    "SHORT_DIGEST": {
        "schema": 1,
        "agents": {
            "deepseek-harness": {
                "version": "0.1.4",
                "url": "https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-0.1.4.tgz",
                "sha512": SHA(64),
            },
        },
    },
    # Correctly signed but policy-invalid: unsupported schema version.
    "SCHEMA_2": {
        "schema": 2,
        "agents": {
            "antigravity": {
                "version": "1.1.30",
                "url": "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.30/linux-arm/cli_linux_arm64.tar.gz",
                "sha512": SHA(128),
            },
        },
    },
}

# Unknown-agent-key entries must be ignored, not fatal (forward compatibility).
MANIFESTS["VALID"]["agents"]["future-agent"] = {
    "version": "9.9.9",
    "url": "https://example.com/future.tgz",
    "sha512": SHA(128),
}


def manifest_bytes(name):
    return json.dumps(MANIFESTS[name], indent=2, sort_keys=True).encode()


def main():
    out = []
    out.append("package com.jarves.mh.runtime")
    out.append("")
    out.append("/**")
    out.append(" * GENERATED by scripts/w3k_sign_fixture.py (window-2 3k) - real Ed25519")
    out.append(" * fixtures for the signed-update-feed tests. DO NOT EDIT BY HAND;")
    out.append(" * regenerate with the script. The signing side is a pure-Python RFC 8032")
    out.append(" * implementation, so a green JVM test proves cross-language compatibility.")
    out.append(" */")
    out.append("internal object TestFixtures3k {")
    out.append(f'    const val KEY_A = "{key_entry(PUBLIC_A)}"')
    out.append(f'    const val KEY_B = "{key_entry(PUBLIC_B)}"')
    for name in ("VALID", "HTTP_URL", "SHORT_DIGEST", "SCHEMA_2"):
        body = manifest_bytes(name)
        public, sig = sign(FIXTURE_SEED_A, body)
        assert public == PUBLIC_A
        out.append(f'    const val MANIFEST_{name} = "{b64(body)}"')
        out.append(f'    const val SIG_{name} = "{b64(minisig_file(public, sig, "agent-updates fixture " + name.lower()))}"')
    # The same valid manifest signed by key B (key-id mismatch vs A).
    body = manifest_bytes("VALID")
    public, sig = sign(FIXTURE_SEED_B, body)
    assert public == PUBLIC_B
    out.append(f'    const val SIG_VALID_BY_B = "{b64(minisig_file(public, sig, "signed by key B"))}"')
    # Minimal two-line form (no trusted comment / global signature).
    public, sig = sign(FIXTURE_SEED_A, body)
    out.append(f'    const val SIG_VALID_MINIMAL = "{b64(minisig_file(public, sig, "", with_comment=False))}"')
    out.append("}")
    target = Path(__file__).resolve().parent.parent / "app/src/test/java/com/jarves/mh/runtime/TestFixtures3k.kt"
    target.write_text("\n".join(out) + "\n")
    print(f"wrote {target} ({len(out)} lines)")
    print(f"public A = {PUBLIC_A.hex()}")
    print(f"public B = {PUBLIC_B.hex()}")


if __name__ == "__main__":
    main()
