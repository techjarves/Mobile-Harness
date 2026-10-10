package com.jarves.mh.runtime

import java.io.File
import java.io.RandomAccessFile

/** Reads at most [maxBytes] from the start of a file without requiring newer Android APIs. */
internal fun File.readHeadBytes(maxBytes: Int): ByteArray {
    if (!isFile || maxBytes <= 0) return ByteArray(0)
    val expected = minOf(length(), maxBytes.toLong()).toInt()
    val bytes = ByteArray(expected)
    var offset = 0
    inputStream().use { input ->
        while (offset < expected) {
            val count = input.read(bytes, offset, expected - offset)
            if (count < 0) break
            offset += count
        }
    }
    return if (offset == expected) bytes else bytes.copyOf(offset)
}

/** Reads at most [maxBytes] from the end of a file without allocating for the whole file. */
internal fun File.readTailText(maxBytes: Int): String {
    if (!isFile || maxBytes <= 0) return ""
    return RandomAccessFile(this, "r").use { input ->
        val byteCount = minOf(input.length(), maxBytes.toLong()).toInt()
        input.seek(input.length() - byteCount)
        val bytes = ByteArray(byteCount)
        input.readFully(bytes)
        bytes.toString(Charsets.UTF_8)
    }
}
