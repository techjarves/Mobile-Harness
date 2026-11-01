package com.jarves.mh.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongBackupPasswordException : IOException("Wrong backup password")
class InvalidBackupException(message: String) : IOException(message)
class NewerBackupException : IOException("This backup was made by a newer version of Mobile Harness")

data class BackupHeader(val version: Int, val encrypted: Boolean, val salt: ByteArray?, val iterations: Int)

/**
 * `.mhbackup` container: a short plaintext header, then the zip payload either as-is or
 * split into AES-256-GCM segments. Segments keep memory bounded for multi-GB backups, and
 * each one authenticates its index plus a final marker so reordering or truncation fails.
 */
object BackupCrypto {
    const val FORMAT_VERSION = 1
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'H'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val FLAG_ENCRYPTED = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    const val SEGMENT_BYTES = 1 shl 20
    const val DEFAULT_ITERATIONS = 210_000

    /** Writes the header and returns the stream the zip payload goes into. */
    fun openWriter(
        output: OutputStream,
        password: CharArray?,
        iterations: Int = DEFAULT_ITERATIONS,
    ): OutputStream {
        val data = DataOutputStream(output)
        data.write(MAGIC)
        data.writeByte(FORMAT_VERSION)
        if (password == null) {
            data.writeByte(0)
            data.flush()
            return output
        }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        data.writeByte(FLAG_ENCRYPTED)
        data.write(salt)
        data.writeInt(iterations)
        data.flush()
        return EncryptingOutputStream(output, deriveKey(password, salt, iterations))
    }

    fun readHeader(input: InputStream): BackupHeader {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            data.readFully(magic)
        } catch (_: EOFException) {
            throw InvalidBackupException("Not a Mobile Harness backup")
        }
        if (!magic.contentEquals(MAGIC)) throw InvalidBackupException("Not a Mobile Harness backup")
        val version = data.readUnsignedByte()
        if (version > FORMAT_VERSION) throw NewerBackupException()
        val flags = data.readUnsignedByte()
        if (flags and FLAG_ENCRYPTED == 0) return BackupHeader(version, false, null, 0)
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val iterations = data.readInt()
        if (iterations !in 1..10_000_000) throw InvalidBackupException("Damaged backup header")
        return BackupHeader(version, true, salt, iterations)
    }

    /** Reads the header and returns the plaintext zip stream. */
    fun openReader(input: InputStream, password: CharArray?): InputStream {
        val header = readHeader(input)
        if (!header.encrypted) return input
        if (password == null || password.isEmpty()) throw WrongBackupPasswordException()
        return DecryptingInputStream(input, deriveKey(password, header.salt!!, header.iterations))
    }

    /** Decrypts only the first segment, so a wrong password is reported before any work starts. */
    fun verifyPassword(input: InputStream, password: CharArray) {
        openReader(input, password).use { it.read() }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(index: Long, final: Boolean): ByteArray =
        ByteBuffer.allocate(9).putLong(index).put(if (final) 1 else 0).array()

    private class EncryptingOutputStream(private val output: OutputStream, private val key: SecretKey) : OutputStream() {
        private val data = DataOutputStream(output)
        private val buffer = ByteArray(SEGMENT_BYTES)
        private val random = SecureRandom()
        private var filled = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (filled == buffer.size) writeSegment(final = false)
                val count = minOf(remaining, buffer.size - filled)
                System.arraycopy(b, offset, buffer, filled, count)
                filled += count
                offset += count
                remaining -= count
            }
        }

        private fun writeSegment(final: Boolean) {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(index, final))
            val encrypted = cipher.doFinal(buffer, 0, filled)
            data.writeByte(if (final) 1 else 0)
            data.writeInt(encrypted.size)
            data.write(nonce)
            data.write(encrypted)
            index++
            filled = 0
        }

        override fun flush() = data.flush()

        override fun close() {
            if (closed) return
            closed = true
            writeSegment(final = true)
            data.flush()
            output.close()
        }
    }

    private class DecryptingInputStream(private val input: InputStream, private val key: SecretKey) : InputStream() {
        private val data = DataInputStream(input)
        private var plain = ByteArray(0)
        private var position = 0
        private var index = 0L
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= plain.size) {
                if (finished) return -1
                nextSegment()
            }
            val count = minOf(len, plain.size - position)
            System.arraycopy(plain, position, b, off, count)
            position += count
            return count
        }

        private fun nextSegment() {
            val final = try {
                data.readUnsignedByte() == 1
            } catch (_: EOFException) {
                throw InvalidBackupException("Backup file is incomplete")
            }
            val length = data.readInt()
            if (length !in (TAG_BITS / 8)..(SEGMENT_BYTES + TAG_BITS / 8)) throw InvalidBackupException("Backup file is damaged")
            val nonce = ByteArray(NONCE_BYTES)
            val encrypted = ByteArray(length)
            try {
                data.readFully(nonce)
                data.readFully(encrypted)
            } catch (_: EOFException) {
                throw InvalidBackupException("Backup file is incomplete")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(index, final))
            plain = try {
                cipher.doFinal(encrypted)
            } catch (_: AEADBadTagException) {
                // The first segment is the password check; later failures mean tampering or damage.
                throw if (index == 0L) WrongBackupPasswordException() else InvalidBackupException("Backup file is damaged")
            }
            position = 0
            index++
            finished = final
        }

        override fun close() = input.close()
    }
}
