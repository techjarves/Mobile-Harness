package com.jarves.mh.ui

import java.io.File
import java.io.RandomAccessFile

internal data class TerminalOutputChunk(val nextOffset: Long, val text: String)

/**
 * Reads at most [maxBytes] of new terminal output from [file], starting at [offset].
 * Returns null when the file has no new bytes; the caller should retry later.
 */
internal fun readTerminalOutputChunk(
    file: File,
    offset: Long,
    maxBytes: Int = 16 * 1024,
): TerminalOutputChunk? {
    if (offset < 0 || maxBytes <= 0) return null
    if (!file.exists()) return null
    return RandomAccessFile(file, "r").use { source ->
        val available = source.length() - offset
        if (available <= 0) return@use null
        val bytes = ByteArray(minOf(available, maxBytes.toLong()).toInt())
        source.seek(offset)
        val count = source.read(bytes)
        if (count <= 0) null else TerminalOutputChunk(offset + count, bytes.decodeToString(0, count))
    }
}
