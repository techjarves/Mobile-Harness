package com.jarves.mh.runtime

/**
 * Per-release allowlist of agent update artifacts this app build vouches for
 * (ISSUE-002 short-term fix, ISSUE-008 dsh slice).
 *
 * The update feeds (Google's agy auto-updater manifest, the npm registry) are
 * *data sources*, not trust anchors: both the version and the digest they
 * carry can be replaced by a compromised endpoint. Only versions pinned here
 * at Mobile Harness release time may be installed, and the downloaded
 * artifact must match the pinned digest — fail-closed otherwise.
 *
 * Since v1.6.0 (roadmap 3k) this table is one of **two** trusted anchors:
 * a project-signed update feed (Ed25519/minisign, keys pinned in
 * [UpdateSigningKeys]) can vouch for new versions between app releases.
 * This table remains the fallback anchor whenever the signed feed is
 * dormant, unreachable, or fails verification.
 */
internal object VerifiedAgentReleases {

    /**
     * Antigravity CLI releases whose tarball SHA-512 (hex) this build vouches
     * for. The initial entry is the exact binary bundled offline — an update
     * can therefore never introduce code the app did not already ship.
     */
    val AGY_TARBALL_SHA512: Map<String, String> = mapOf(
        "1.1.27" to "ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9",
    )

    /**
     * DeepSeek Harness npm tarball digests (hex SHA-512), pinned from the
     * registry metadata at release time. Updates install from the verified
     * local tarball instead of an open-ended `npm install` (ISSUE-008).
     */
    val DSH_TARBALL_SHA512: Map<String, String> = mapOf(
        "0.1.2-rc.1" to "44fab8f13cf1bf0a5d4fdffb5b5b5b8590c132678af9bc43ad7f5ca900b6ed6c7f2eab60245f0f49addbdf1ae253ca31b6f870626936d70d9c7a3dee310ae754",
    )

    fun agyDigest(version: String): String? = AGY_TARBALL_SHA512[version]

    fun dshDigest(version: String): String? = DSH_TARBALL_SHA512[version]

    /** True when this build may offer an update to [version] at all. */
    fun isVerifiedAgyRelease(version: String): Boolean = version in AGY_TARBALL_SHA512

    fun isVerifiedDshRelease(version: String): Boolean = version in DSH_TARBALL_SHA512
}
