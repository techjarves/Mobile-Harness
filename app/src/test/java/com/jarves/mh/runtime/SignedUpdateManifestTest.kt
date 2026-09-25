package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * ISSUE-002 long-term fix (roadmap 3k): the project-signed agent update
 * feed. Every fixture here was produced by scripts/w3k_sign_fixture.py —
 * a pure-Python RFC 8032 implementation — so a green suite proves the
 * shipped Kotlin verifier interoperates with an independent signer, and
 * that every policy or tamper path fails closed.
 */
class SignedUpdateManifestTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun keyA(): com.jarves.mh.crypto.Minisign.PublicKey =
        com.jarves.mh.crypto.Minisign.parsePublicKeyEntry(TestFixtures3k.KEY_A)!!

    private fun keyB(): com.jarves.mh.crypto.Minisign.PublicKey =
        com.jarves.mh.crypto.Minisign.parsePublicKeyEntry(TestFixtures3k.KEY_B)!!

    private fun parseValid(): SignedUpdateFeed? = SignedUpdateManifest.parse(
        b64(TestFixtures3k.MANIFEST_VALID),
        b64(TestFixtures3k.SIG_VALID),
        listOf(keyA()),
    )

    @Test
    fun `a correctly signed manifest verifies and all three agents parse`() {
        val feed = parseValid()
        assertNotNull(feed)
        assertEquals("2.1.264", feed!!.claudeCode?.version)
        assertEquals("0.1.3-rc.1", feed.deepSeekHarness?.version)
        assertEquals("1.1.28", feed.antigravity?.version)
        assertEquals(
            "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.28/linux-arm/cli_linux_arm64.tar.gz",
            feed.antigravity?.url,
        )
        assertEquals(128, feed.antigravity?.sha512?.length)
        // Unknown agent keys in the manifest are ignored (forward compat).
        assertEquals(3, listOfNotNull(feed.claudeCode, feed.deepSeekHarness, feed.antigravity).size)
    }

    @Test
    fun `a single flipped manifest byte is rejected`() {
        val body = b64(TestFixtures3k.MANIFEST_VALID)
        body[10] = (body[10].toInt() xor 0x01).toByte()
        assertNull(SignedUpdateManifest.parse(body, b64(TestFixtures3k.SIG_VALID), listOf(keyA())))
    }

    @Test
    fun `a single flipped signature byte is rejected`() {
        val sig = b64(TestFixtures3k.SIG_VALID)
        sig[50] = (sig[50].toInt() xor 0x01).toByte()
        assertNull(SignedUpdateManifest.parse(b64(TestFixtures3k.MANIFEST_VALID), sig, listOf(keyA())))
    }

    @Test
    fun `a signature by a different key is rejected by key id`() {
        // Signed correctly by key B, but only key A is trusted.
        assertNull(
            SignedUpdateManifest.parse(
                b64(TestFixtures3k.MANIFEST_VALID),
                b64(TestFixtures3k.SIG_VALID_BY_B),
                listOf(keyA()),
            ),
        )
        // ...and trusted as B it does verify (control).
        assertNotNull(
            SignedUpdateManifest.parse(
                b64(TestFixtures3k.MANIFEST_VALID),
                b64(TestFixtures3k.SIG_VALID_BY_B),
                listOf(keyB()),
            ),
        )
    }

    @Test
    fun `the minimal two line signature form is accepted`() {
        assertNotNull(
            SignedUpdateManifest.parse(
                b64(TestFixtures3k.MANIFEST_VALID),
                b64(TestFixtures3k.SIG_VALID_MINIMAL),
                listOf(keyA()),
            ),
        )
    }

    @Test
    fun `a correctly signed manifest with an http url is still rejected`() {
        assertNull(
            SignedUpdateManifest.parse(
                b64(TestFixtures3k.MANIFEST_HTTP_URL),
                b64(TestFixtures3k.SIG_HTTP_URL),
                listOf(keyA()),
            ),
        )
    }

    @Test
    fun `a correctly signed manifest with a short digest is still rejected`() {
        assertNull(
            SignedUpdateManifest.parse(
                b64(TestFixtures3k.MANIFEST_SHORT_DIGEST),
                b64(TestFixtures3k.SIG_SHORT_DIGEST),
                listOf(keyA()),
            ),
        )
    }

    @Test
    fun `a correctly signed manifest with an unsupported schema is still rejected`() {
        assertNull(
            SignedUpdateManifest.parse(
                b64(TestFixtures3k.MANIFEST_SCHEMA_2),
                b64(TestFixtures3k.SIG_SCHEMA_2),
                listOf(keyA()),
            ),
        )
    }

    @Test
    fun `no trusted keys means no feed - dormant by design`() {
        assertNull(
            SignedUpdateManifest.parse(
                b64(TestFixtures3k.MANIFEST_VALID),
                b64(TestFixtures3k.SIG_VALID),
                emptyList(),
            ),
        )
    }

    @Test
    fun `garbage inputs never throw and never verify`() {
        assertNull(SignedUpdateManifest.parse(ByteArray(0), ByteArray(0), listOf(keyA())))
        assertNull(SignedUpdateManifest.parse("not json".toByteArray(), b64(TestFixtures3k.SIG_VALID), listOf(keyA())))
        assertNull(SignedUpdateManifest.parse(b64(TestFixtures3k.MANIFEST_VALID), "junk".toByteArray(), listOf(keyA())))
    }

    @Test
    fun `an oversized manifest is rejected before parsing`() {
        val huge = ByteArray(64 * 1024 + 1) { ' '.code.toByte() }
        assertNull(SignedUpdateManifest.parse(huge, b64(TestFixtures3k.SIG_VALID), listOf(keyA())))
    }

    @Test
    fun `the production signing key list ships empty - the gate is locked`() {
        // Same honest-gate pattern as the 3i rootfs digest: no real key may
        // be invented by a contributor; activation is the owner's documented
        // step (docs/release/signing-agent-updates.md). This test fails the
        // moment someone adds a key without going through that procedure.
        assertEquals(0, UpdateSigningKeys.MINISIGN_PUBLIC_KEYS.size)
        assertEquals(0, UpdateSigningKeys.publicKeys.size)
    }

    @Test
    fun `with production defaults the feed stays dormant and fails closed`() {
        // Default trusted keys = UpdateSigningKeys.publicKeys (empty in this
        // build): even a perfectly signed manifest must yield null.
        assertNull(
            SignedUpdateManifest.parse(b64(TestFixtures3k.MANIFEST_VALID), b64(TestFixtures3k.SIG_VALID)),
        )
    }
}
