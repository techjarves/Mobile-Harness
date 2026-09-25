package com.jarves.mh.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Minimal, self-contained, verify-only Ed25519 (RFC 8032).
 *
 * Why it exists: agent update manifests (roadmap 3k, ISSUE-002 long-term
 * fix) are signed with Ed25519 in minisign's format, and Android's
 * java.security EdDSA only exists from API 33+ while this app supports
 * API 28+. Verification is the only operation ever needed on device —
 * signing happens offline with the real minisign tool.
 *
 * Design notes (kept deliberate and reviewable):
 * - BigInteger arithmetic that follows the RFC 8032 section 5.1 pseudocode
 *   as literally as practical. This is intentionally NOT constant-time:
 *   Ed25519 verification processes only public data (public key, signature,
 *   message), so timing side channels reveal nothing secret.
 * - Points use extended homogeneous coordinates (x = X/Z, y = Y/Z,
 *   xy = T/Z) with the complete addition formulas for a = -1 twisted
 *   Edwards curves, so [pointAdd] handles every input pair — including
 *   P + P and P + (-P) — with no special cases to get wrong.
 * - Non-canonical encodings are rejected exactly as the RFC requires:
 *   y >= p, S >= L, no square root for x, x = 0 with the sign bit set.
 */
internal object Ed25519 {

    /** Field prime p = 2^255 - 19. */
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19L))

    /** Group order L = 2^252 + 27742317777372353535851937790883648493. */
    private val L: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** d = -121665/121666 mod p. */
    private val D: BigInteger = BigInteger.valueOf(-121665L)
        .multiply(BigInteger.valueOf(121666L).modInverse(P))
        .mod(P)

    /** sqrt(-1) = 2^((p-1)/4), used to fix up the square-root candidate. */
    private val SQRT_M1: BigInteger =
        BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4L)), P)

    private val ZERO = BigInteger.ZERO
    private val ONE = BigInteger.ONE
    private val TWO = BigInteger.TWO

    /** Extended homogeneous point; the identity is (0, 1, 1, 0). */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(ZERO, ONE, ONE, ZERO)

    /** Base point: y = 4/5 with x recovered at even parity (RFC 8032 5.1). */
    private val BASE: Point = run {
        val y = BigInteger.valueOf(4L).multiply(BigInteger.valueOf(5L).modInverse(P)).mod(P)
        val x = recoverX(y, sign = false) ?: error("Ed25519 base point recovery failed (implementation bug)")
        Point(x, y, ONE, x.multiply(y).mod(P))
    }

    /**
     * Verifies an Ed25519 signature against a 32-byte public key and the
     * message. Signature = 64 bytes, R || S, both little-endian
     * (RFC 8032 5.1.7). Fails closed on any malformed input; never throws.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decodePoint(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decodePoint(rBytes) ?: return false
        val s = littleEndianToBigInteger(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val h = littleEndianToBigInteger(sha512(rBytes + publicKey + message)).mod(L)
        // [S]B ?= R + [h]A — compared without modular inversion by
        // cross-multiplying the Z coordinates of both sides.
        val left = scalarMultiply(s, BASE)
        val right = pointAdd(r, scalarMultiply(h, a))
        return left.x.multiply(right.z).mod(P) == right.x.multiply(left.z).mod(P) &&
            left.y.multiply(right.z).mod(P) == right.y.multiply(left.z).mod(P)
    }

    // ---- point decoding (RFC 8032 5.1.3) --------------------------------------

    private fun decodePoint(bytes: ByteArray): Point? {
        val yRaw = littleEndianToBigInteger(bytes)
        val sign = yRaw.testBit(255)
        val y = yRaw.and(ONE.shiftLeft(255).subtract(ONE))
        if (y >= P) return null // non-canonical y
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, ONE, x.multiply(y).mod(P))
    }

    /**
     * Recovers x from y on -x^2 + y^2 = 1 + d*x^2*y^2 with the requested
     * parity, or null when y is not on the curve. Avoids modular inversion:
     * x = u * v^3 * (u * v^7)^((p-5)/8) is the square-root candidate for
     * primes congruent to 5 mod 8 (p = 2^255 - 19 qualifies).
     */
    private fun recoverX(y: BigInteger, sign: Boolean): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(ONE).mod(P)
        val v = D.multiply(y2).add(ONE).mod(P)
        val v3 = v.multiply(v).multiply(v).mod(P)
        val v7 = v3.multiply(v3).multiply(v).mod(P)
        var x = u.multiply(v3)
            .multiply(u.multiply(v7).modPow(P.subtract(BigInteger.valueOf(5L)).divide(BigInteger.valueOf(8L)), P))
            .mod(P)
        val vx2 = v.multiply(x).multiply(x).mod(P)
        when {
            vx2 == u -> Unit
            vx2 == u.negate().mod(P) -> x = x.multiply(SQRT_M1).mod(P)
            else -> return null
        }
        if (x == ZERO && sign) return null
        if (x.testBit(0) != sign) x = P.subtract(x)
        return x
    }

    // ---- point arithmetic ------------------------------------------------------

    /** Complete addition for a = -1 twisted Edwards curves (no edge cases). */
    private fun pointAdd(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(TWO).multiply(D).multiply(q.t).mod(P)
        val d = p.z.multiply(TWO).multiply(q.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    /** Double-and-add scalar multiplication (public data only — not secret). */
    private fun scalarMultiply(k: BigInteger, point: Point): Point {
        var result = IDENTITY
        var addend = point
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) result = pointAdd(result, addend)
            addend = pointAdd(addend, addend)
        }
        return result
    }

    // ---- helpers ----------------------------------------------------------------

    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
        var result = ZERO
        for (i in bytes.indices.reversed()) {
            result = result.shiftLeft(8).or(BigInteger.valueOf(bytes[i].toLong() and 0xFFL))
        }
        return result
    }

    private fun sha512(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(bytes)
}
