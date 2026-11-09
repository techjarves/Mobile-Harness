package com.jarves.mh.ui

import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

internal data class TerminalOutputChunk(val nextOffset: Long, val text: String)

/**
 * Reads at most [maxBytes] of new terminal output from [file], starting at [offset].
 * Returns null when the file has no new bytes yet, does not exist, or disappears while
 * the pump is still creating it; the caller should retry later.
 */
internal fun readTerminalOutputChunk(
    file: File,
    offset: Long,
    maxBytes: Int = 16 * 1024,
): TerminalOutputChunk? {
    if (offset < 0 || maxBytes <= 0) return null
    if (!file.exists()) return null
    if (file.length() <= offset) return null
    return try {
        RandomAccessFile(file, "r").use { source ->
            val available = source.length() - offset
            if (available <= 0) return@use null
            val bytes = ByteArray(minOf(available, maxBytes.toLong()).toInt())
            source.seek(offset)
            val count = source.read(bytes)
            if (count <= 0) null else TerminalOutputChunk(offset + count, bytes.decodeToString(0, count))
        }
    } catch (_: FileNotFoundException) {
        // The pump may replace the file between exists() and open(); retry next poll.
        null
    }
}

/**
 * Drains every chunk currently available in [file], starting at [startOffset], and returns
 * the offset after the last byte. [append] receives each decoded chunk in order.
 *
 * This is the post-exit drain: it recovers bytes the pump wrote after the last poll,
 * including the `__POCKETDEV_CWD_*` marker. The loop stops at [maxTotalBytes] so a child that
 * keeps writing cannot make shutdown unbounded.
 */
internal inline fun drainTerminalOutput(
    file: File,
    startOffset: Long,
    maxBytes: Int = 16 * 1024,
    maxTotalBytes: Int = 1024 * 1024,
    append: (String) -> Unit,
): Long {
    var offset = startOffset
    var total = 0
    while (total < maxTotalBytes) {
        val chunk = readTerminalOutputChunk(file, offset, minOf(maxBytes, maxTotalBytes - total)) ?: break
        if (chunk.text.isNotEmpty()) append(chunk.text)
        total += (chunk.nextOffset - offset).toInt()
        offset = chunk.nextOffset
    }
    return offset
}
