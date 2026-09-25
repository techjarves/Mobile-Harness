package com.jarves.mh.runtime

import com.jarves.mh.crypto.Minisign

/**
 * minisign public keys trusted to sign the agent update manifest
 * (roadmap 3k — the long-term fix for ISSUE-002).
 *
 * Each entry is the single base64 line of a minisign public key file —
 * exactly what `minisign -G` writes to `<name>.pub` (base64 of
 * keyId[8] || publicKey[32]).
 *
 * DELIBERATELY EMPTY UNTIL ACTIVATION — fail-closed, same honest-gate
 * pattern as the 3i rootfs digest: the owner must generate the real key
 * pair offline (docs/release/signing-agent-updates.md, section 1); an
 * invented key checked in here would defeat the entire point of a
 * project-controlled trust anchor. While this list is empty the signed
 * feed stays dormant and agent updates fall back to the per-release
 * pinned allowlist ([VerifiedAgentReleases]) — i.e. exactly the
 * behavior shipped since v1.2.0.
 *
 * Rotation: append the new key alongside the old one in an app release,
 * sign with the new key from then on, and drop the old key in the next
 * release after one full cycle (runbook section 4).
 */
internal object UpdateSigningKeys {

    val MINISIGN_PUBLIC_KEYS: List<String> = emptyList()

    /** Parsed and cached; a malformed entry is dropped, never trusted. */
    val publicKeys: List<Minisign.PublicKey> by lazy {
        MINISIGN_PUBLIC_KEYS.mapNotNull { Minisign.parsePublicKeyEntry(it) }
    }
}
