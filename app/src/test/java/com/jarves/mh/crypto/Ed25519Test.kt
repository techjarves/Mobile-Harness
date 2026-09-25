package com.jarves.mh.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ed25519 verifier against the official RFC 8032 section 7.1 test vectors,
 * plus the rejection paths the RFC mandates (non-canonical y, S >= L) and
 * generic tamper rejection. If the implementation is wrong in any way —
 * point decoding, field arithmetic, scalar multiplication, the base point —
 * these vectors cannot pass.
 */
class Ed25519Test {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
    }

    // RFC 8032 7.1 TEST 1 (empty message)
    private val pk1 = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val sig1 = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
            "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    )

    // RFC 8032 7.1 TEST 2 (message 0x72)
    private val pk2 = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    private val sig2 = hex(
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
            "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
    )

    // RFC 8032 7.1 TEST 3 (message af82)
    private val pk3 = hex("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025")
    private val sig3 = hex(
        "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
            "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
    )

    @Test
    fun `rfc8032 test vector 1 empty message verifies`() {
        assertTrue(Ed25519.verify(pk1, ByteArray(0), sig1))
    }

    @Test
    fun `rfc8032 test vector 2 one byte message verifies`() {
        assertTrue(Ed25519.verify(pk2, byteArrayOf(0x72), sig2))
    }

    @Test
    fun `rfc8032 test vector 3 two byte message verifies`() {
        assertTrue(Ed25519.verify(pk3, hex("af82"), sig3))
    }

    @Test
    fun `a flipped message bit is rejected`() {
        assertTrue(Ed25519.verify(pk2, byteArrayOf(0x72), sig2))
        assertFalse(Ed25519.verify(pk2, byteArrayOf(0x73), sig2))
    }

    @Test
    fun `a flipped signature bit is rejected`() {
        val tampered = sig1.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFalse(Ed25519.verify(pk1, ByteArray(0), tampered))
    }

    @Test
    fun `a signature under the wrong public key is rejected`() {
        assertFalse(Ed25519.verify(pk2, ByteArray(0), sig1))
    }

    @Test
    fun `signature with s greater or equal to L is rejected`() {
        // S := 2^256 - 1 (all 0xFF) — larger than the group order.
        val oversizedS = sig1.copyOf()
        for (i in 32 until 64) oversizedS[i] = 0xFF.toByte()
        assertFalse(Ed25519.verify(pk1, ByteArray(0), oversizedS))
    }

    @Test
    fun `non-canonical r point with y greater or equal to p is rejected`() {
        // y := 2^255 - 1 > p = 2^255 - 19 must be rejected before any math.
        val nonCanonical = sig1.copyOf()
        for (i in 0 until 31) nonCanonical[i] = 0xFF.toByte()
        nonCanonical[31] = 0x7F.toByte()
        assertFalse(Ed25519.verify(pk1, ByteArray(0), nonCanonical))
    }

    @Test
    fun `an r point that is not on the curve is rejected`() {
        // y := 2 keeps the encoding canonical but (almost surely) off-curve.
        val offCurve = sig1.copyOf()
        for (i in 0 until 32) offCurve[i] = 0
        offCurve[1] = 0x02.toByte()
        assertFalse(Ed25519.verify(pk1, ByteArray(0), offCurve))
    }

    @Test
    fun `malformed input lengths are rejected`() {
        assertFalse(Ed25519.verify(pk1.copyOfRange(0, 31), ByteArray(0), sig1))
        assertFalse(Ed25519.verify(pk1, ByteArray(0), sig1.copyOfRange(0, 63)))
        assertFalse(Ed25519.verify(pk1 + pk1, ByteArray(0), sig1))
        assertFalse(Ed25519.verify(pk1, ByteArray(0), sig1 + sig1))
    }
}
