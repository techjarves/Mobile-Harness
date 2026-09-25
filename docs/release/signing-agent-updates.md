# Signing agent updates (Ed25519 / minisign) — roadmap 3k, ISSUE-002

This document is the complete runbook for the **project-signed agent update
feed**: how to generate the offline key pair, how to activate it in an app
release, how to publish a signed update without shipping a new APK, and how
to rotate keys. It closes ISSUE-002 (P1, CVSS 7.0 — unsigned agent update
chain / full TOFU) end to end.

## 0. Threat model and design

Before 3k, an update could only be offered when its tarball digest was
pinned inside the app build (`VerifiedAgentReleases`). That is fail-closed
but heavy: **every agent release required an app release**. The signed feed
inverts the anchor: the app pins one **offline Ed25519 public key**
(`UpdateSigningKeys`), and a manifest signed by that key can introduce new
agent versions at any time.

What an attacker can and cannot do:

| Compromised asset | Consequence |
|---|---|
| GitHub `agent-updates` branch, raw CDN, DNS, or MITM | Cannot push an update: the manifest must carry a valid minisign signature from the offline key. At worst a **replay** of an older *properly signed* manifest — and replays cannot downgrade (a version is only offered when newer than the installed one) and cannot swap digests. |
| The signing key | Game over for update integrity — hence the key lives only on an offline machine and rotation is section 4. |
| A signed-but-malicious manifest from the project itself | Out of scope: the feed is authored by the same trust principal that ships the APK. |
| npm registry / Google's agy auto-updater endpoint | Unchanged from the v1.2.0 posture: these are *data sources*, only consulted through pinned digests. |

Why minisign and not cosign/Sigstore: the signature covers the **exact
served bytes** of a small JSON file, so no envelope, no canonicalization, no
online verification service is needed; the tool is battle-tested since
2015; trusted comments give the release channel a human-readable, signed
changelog line. The verifier (`crypto/Ed25519.kt`, `crypto/Minisign.kt`) is
~370 lines of reviewable pure Kotlin because Android's `java.security`
EdDSA only exists from API 33+ while this app supports API 28+.

## 1. One-time: generate the offline key pair

On an **offline machine** (the signing host — never a developer laptop with
the repo, never CI):

```bash
minisign -G -p agent-updates.pub -s agent-updates.key
```

- `agent-updates.key` (secret) — stays on that machine, encrypted with a
  strong passphrase minisign asks for. **It is never committed.**
- `agent-updates.pub` (public) — safe to publish anywhere.

Back up the secret key per your key-management policy (encrypted USB,
safe). Losing it forces a rotation (section 4) — annoying, not fatal.

## 2. One-time: activate the key in an app release

1. Open `app/src/main/java/com/jarves/mh/runtime/UpdateSigningKeys.kt`.
2. Copy the **single base64 line** from `agent-updates.pub` (the second
   line of the file, not the `untrusted comment` header) into
   `MINISIGN_PUBLIC_KEYS`.
3. Ship it in the next app release. Until that release reaches users the
   feed is dormant: `UpdateSigningKeys.publicKeys` is empty, the app never
   even fetches the manifest, and updates keep flowing through the pinned
   allowlist — exactly the pre-3k behavior. The gate is locked by a unit
   test (`SignedUpdateManifestTest` — "the production signing key list
   ships empty"), which fails if a key is added without this procedure.

The feed URL is pinned in `RuntimeInstaller`:
`https://raw.githubusercontent.com/techjarves/Mobile-Harness/agent-updates/agent-updates-manifest.json`
(plus `.minisig`). Create the `agent-updates` branch with the two files
below in the same release that activates the key, so the first fetch
succeeds.

## 3. Routine: publish a signed agent update

1. Prepare `manifest.json` (schema 1; unknown agent keys are ignored
   forward-compatibly; `generatedAt` is informational):

   ```json
   {
     "schema": 1,
     "generatedAt": "2026-10-01",
     "agents": {
       "antigravity": {
         "version": "1.1.28",
         "url": "https://storage.googleapis.com/antigravity-public/.../cli_linux_arm64.tar.gz",
         "sha512": "<128 lowercase hex chars of the tarball>"
       }
     }
   }
   ```

   Field rules (enforced again on device, fail-closed): `version` matches
   `[0-9A-Za-z.+-]{1,64}`; `url` is `https://` and ≤ 2048 chars;
   `sha512` is exactly 128 hex chars. Compute the digest with
   `sha512sum <tarball>`.

2. Sign and publish from the offline machine:

   ```bash
   ./scripts/sign-agent-manifest.sh manifest.json /path/to/agent-updates.key
   ```

   The script sanity-checks the manifest, signs it with minisign (detached
   `.minisig`, trusted comment `agent-updates <date>`), verifies the
   signature against the `.pub`, and prints the exact git commands to
   commit both files to the `agent-updates` branch. Pushing that branch IS
   publishing.

3. Rollback = remove the entry from the manifest and re-sign. Devices only
   ever install versions newer than what they run, and the download itself
   is digest-verified, so a rolled-back feed can at most stop offering the
   update.

## 4. Key rotation

1. Generate the next key pair offline (`minisign -G`).
2. Add the new public key **alongside** the old one in
   `UpdateSigningKeys.MINISIGN_PUBLIC_KEYS` and ship an app release.
3. From then on sign manifests with the new key (the app accepts either
   during the transition; the 8-byte key id inside each signature selects
   which pinned key is used).
4. After one full release cycle (every supported install updated), remove
   the old public key in the next app release.

## 5. Verification guarantees (what the tests lock in)

- `Ed25519Test` — RFC 8032 official test vectors plus every mandated
  rejection (non-canonical `y >= p`, `S >= L`, off-curve points, tampered
  bytes, wrong keys, malformed lengths).
- `MinisignTest` — the exact file format, key-id matching, comment/global
  signature pairing, CRLF/no-trailing-newline tolerance, and structural
  strictness.
- `SignedUpdateManifestTest` — end-to-end fixtures generated by
  `scripts/w3k_sign_fixture.py`, an **independent pure-Python RFC 8032
  signer** (cross-implementation compatibility proof), plus the
  policy gates: http URL, short digest, schema 2, wrong key, flipped
  byte anywhere, oversized body, empty trust list — all must return null.
- `TestFixtures3k` is the only generated file and is regenerated solely by
  that script.

Device-side acceptance (run once, on the reference rooted phone, when the
key is activated): install an older pinned agent version, publish a signed
newer one on the branch, confirm the update is offered, installs, and that
flipping one byte of the served manifest makes the offer disappear without
a crash.
