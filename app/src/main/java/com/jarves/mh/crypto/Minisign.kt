package com.jarves.mh.crypto

import java.util.Base64

/**
 * minisign (https://jedisct1.github.io/minisign/) detached-signature
 * format — parse + verify only. Signing happens offline with the real
 * minisign tool; the release runbook lives at
 * docs/release/signing-agent-updates.md.
 *
 * File layout (as minisign has written it since 2015):
 *
 *     untrusted comment: <arbitrary attacker-controlled text>
 *     <base64 of "Ed" || keyId[8] || signature[64]>   (74 bytes -> 100 chars)
 *     trusted comment: <release-manager text>
 *     <base64 of globalSignature[64]>
 *
 * Trust rules (equal to or stricter than minisign itself):
 * - the 8-byte key id must equal the pinned key entry's id — for genuine
 *   minisign pairs both are BLAKE2b-64(publicKey) computed by minisign at
 *   key-generation time, so honest files always match (the id is routing
 *   metadata, not a MAC; the signature is the actual proof);
 * - the Ed25519 signature must verify under the pinned public key over the
 *   exact file bytes;
 * - a trusted comment and its global signature must appear together — the
 *   global signature covers comment || signature with the same key, where
 *   "comment" is exactly what follows the "trusted comment: " prefix
 *   (space included in the prefix, matching minisign byte for byte). A
 *   file without either (the minimal two-line form) is accepted and relies
 *   on the main signature alone.
 */
internal object Minisign {

    /** A parsed minisign public key entry: base64(keyId[8] || key[32]). */
    class PublicKey(val keyId: ByteArray, val key: ByteArray) {
        init {
            require(keyId.size == 8 && key.size == 32) { "malformed minisign public key entry" }
        }

        fun sameKeyId(other: ByteArray): Boolean = keyId.contentEquals(other)
    }

    /** A parsed .minisig file. comment/globalSignature are both null or both present. */
    class SignatureFile(
        val keyId: ByteArray,
        val signature: ByteArray,
        val trustedComment: ByteArray?,
        val globalSignature: ByteArray?,
    )

    /** Parses the base64 line of a minisign public key file; null when malformed. */
    fun parsePublicKeyEntry(base64Entry: String): PublicKey? {
        val decoded = runCatching { Base64.getDecoder().decode(base64Entry.trim()) }.getOrNull() ?: return null
        if (decoded.size != 40) return null
        return PublicKey(decoded.copyOfRange(0, 8), decoded.copyOfRange(8, 40))
    }

    /** Parses a .minisig file; null on any structural violation. */
    fun parseSignatureFile(bytes: ByteArray): SignatureFile? {
        var lines = String(bytes, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines = lines.dropLast(1)
        if (lines.size != 2 && lines.size != 4) return null
        if (!lines[0].startsWith("untrusted comment:")) return null
        val signatureBlob = decode(lines[1]) ?: return null
        if (signatureBlob.size != 74) return null
        if (signatureBlob[0] != 'E'.code.toByte() || signatureBlob[1] != 'd'.code.toByte()) return null
        val keyId = signatureBlob.copyOfRange(2, 10)
        val signature = signatureBlob.copyOfRange(10, 74)
        if (lines.size == 2) return SignatureFile(keyId, signature, null, null)
        if (!lines[2].startsWith("trusted comment: ")) return null
        val trustedComment = lines[2].removePrefix("trusted comment: ").toByteArray(Charsets.UTF_8)
        val globalSignature = decode(lines[3]) ?: return null
        if (globalSignature.size != 64) return null
        return SignatureFile(keyId, signature, trustedComment, globalSignature)
    }

    /** All-or-nothing verification of a signature file against one pinned key. */
    fun verify(publicKey: PublicKey, message: ByteArray, signatureFile: SignatureFile): Boolean {
        if (!publicKey.sameKeyId(signatureFile.keyId)) return false
        if (!Ed25519.verify(publicKey.key, message, signatureFile.signature)) return false
        val comment = signatureFile.trustedComment
        val global = signatureFile.globalSignature
        if (comment == null && global == null) return true
        if (comment == null || global == null) return false
        return Ed25519.verify(publicKey.key, comment + signatureFile.signature, global)
    }

    private fun decode(line: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(line) }.getOrNull()
}
