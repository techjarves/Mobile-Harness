package com.jarves.mh.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * minisign signature-file parsing and verification. Crypto correctness is
 * covered by Ed25519Test against RFC 8032 vectors; these tests cover the
 * file format, the strictness rules, and their fail-closed combinations.
 */
class MinisignTest {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
    }

    private fun b64(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)

    // RFC 8032 7.1 TEST 2 as the crypto anchor for these format tests.
    private val keyBytes = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    private val signature64 = hex(
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
            "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
    )
    private val message = byteArrayOf(0x72)
    private val keyId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val otherKeyId = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)

    private fun key() = Minisign.parsePublicKeyEntry(b64(keyId + keyBytes))!!

    private fun sigFileBytes(
        keyIdForSig: ByteArray = keyId,
        withComment: Boolean = true,
        trustedComment: String = "release 1.2.3",
        lineSeparator: String = "\n",
        trailingNewline: Boolean = true,
    ): ByteArray {
        val blob = "Ed".toByteArray() + keyIdForSig + signature64
        val lines = mutableListOf("untrusted comment: whatever", b64(blob))
        if (withComment) {
            lines += "trusted comment: $trustedComment"
            // Placeholder global signature: crypto-level tests override it or
            // expect failure; parse-level tests do not care about its value.
            lines += b64(ByteArray(64))
        }
        val text = lines.joinToString(lineSeparator) + if (trailingNewline) lineSeparator else ""
        return text.toByteArray()
    }

    // ---- public key parsing ---------------------------------------------------

    @Test
    fun `a genuine public key entry parses into keyid and key`() {
        val key = Minisign.parsePublicKeyEntry(b64(keyId + keyBytes))
        assertNotNull(key)
        assertTrue(key!!.sameKeyId(keyId))
        assertTrue(key.key.contentEquals(keyBytes))
    }

    @Test
    fun `public key entries with wrong length or bad base64 are rejected`() {
        assertNull(Minisign.parsePublicKeyEntry(b64(keyId + keyBytes + byteArrayOf(1))))    // 41 bytes
        assertNull(Minisign.parsePublicKeyEntry(b64(keyId)))                                 // 8 bytes
        assertNull(Minisign.parsePublicKeyEntry("not base64 !!"))
        assertNull(Minisign.parsePublicKeyEntry(""))
    }

    // ---- signature file parsing -------------------------------------------------

    @Test
    fun `a full four line file parses with comment and global signature`() {
        val parsed = Minisign.parseSignatureFile(sigFileBytes())
        assertNotNull(parsed)
        assertTrue(parsed!!.keyId.contentEquals(keyId))
        assertTrue(parsed.signature.contentEquals(signature64))
        assertEquals("release 1.2.3", String(parsed.trustedComment!!))
        assertNotNull(parsed.globalSignature)
    }

    @Test
    fun `the minimal two line file parses without comment or global signature`() {
        val parsed = Minisign.parseSignatureFile(sigFileBytes(withComment = false))
        assertNotNull(parsed)
        assertNull(parsed!!.trustedComment)
        assertNull(parsed.globalSignature)
    }

    @Test
    fun `crlf line endings and missing trailing newline are accepted`() {
        assertNotNull(Minisign.parseSignatureFile(sigFileBytes(lineSeparator = "\r\n")))
        assertNotNull(Minisign.parseSignatureFile(sigFileBytes(trailingNewline = false)))
    }

    @Test
    fun `structurally invalid files are rejected`() {
        assertNull(Minisign.parseSignatureFile(ByteArray(0)))
        assertNull(Minisign.parseSignatureFile("garbage".toByteArray()))
        // Missing untrusted-comment header.
        assertNull(Minisign.parseSignatureFile("hello\nworld\n".toByteArray()))
        // Three lines is neither form.
        assertNull(Minisign.parseSignatureFile(sigFileBytes().toString(Charsets.UTF_8).split("\n").take(3).joinToString("\n").toByteArray()))
        // Bad base64 on the signature line.
        assertNull(Minisign.parseSignatureFile("untrusted comment: x\n???not-base64???\n".toByteArray()))
        // Wrong algorithm id ("Rx" instead of "Ed").
        val wrongAlgo = "Rx".toByteArray() + keyId + signature64
        assertNull(Minisign.parseSignatureFile("untrusted comment: x\n${b64(wrongAlgo)}\n".toByteArray()))
        // Signature blob of the wrong total length.
        val shortBlob = "Ed".toByteArray() + keyId + signature64.copyOfRange(0, 40)
        assertNull(Minisign.parseSignatureFile("untrusted comment: x\n${b64(shortBlob)}\n".toByteArray()))
        // Five lines with content is neither form.
        val five = sigFileBytes().toString(Charsets.UTF_8) + "extra line\n"
        assertNull(Minisign.parseSignatureFile(five.toByteArray()))
    }

    // ---- verification ------------------------------------------------------------

    @Test
    fun `minimal form with matching key id and valid signature verifies`() {
        val file = Minisign.parseSignatureFile(sigFileBytes(withComment = false))!!
        assertTrue(Minisign.verify(key(), message, file))
    }

    @Test
    fun `a key id mismatch is rejected even with a valid signature`() {
        val file = Minisign.parseSignatureFile(sigFileBytes(keyIdForSig = otherKeyId, withComment = false))!!
        assertFalse(Minisign.verify(key(), message, file))
    }

    @Test
    fun `full form with a wrong global signature is rejected`() {
        // Main signature is valid, but the global signature (placeholder zeros)
        // does not cover comment || signature.
        val file = Minisign.parseSignatureFile(sigFileBytes())!!
        assertFalse(Minisign.verify(key(), message, file))
    }

    @Test
    fun `a trusted comment without its global signature is rejected`() {
        // The comment/global-signature pair must appear together (3 lines is
        // neither the minimal nor the full form). End-to-end verification of
        // a real global signature (Python-generated) lives in
        // SignedUpdateManifestTest via TestFixtures3k.SIG_VALID.
        val lines = listOf(
            "untrusted comment: x",
            b64("Ed".toByteArray() + keyId + signature64),
            "trusted comment: t",
        ).joinToString("\n") + "\n"
        assertNull(Minisign.parseSignatureFile(lines.toByteArray()))
    }
}
