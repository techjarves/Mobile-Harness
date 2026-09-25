package com.jarves.mh.runtime

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The single "follow a growing output file" implementation (ISSUE-013).
 *
 * Seven call sites used to duplicate this loop (the three agent bridges, the
 * agy hello probe, the agy OAuth controller, the installer's guest-command
 * reader and the two terminal readers), each reopening the file on every poll
 * and most of them parking a thread at a fixed 50–100 ms.
 *
 * Contract — deliberately identical to the previous inline loops:
 *  - starts at offset 0 and only moves forward;
 *  - keeps ONE [RandomAccessFile] open for the whole tail;
 *  - adaptive polling: 25 ms while data is flowing, backing off to 250 ms
 *    while idle (an idle session wakes the CPU 5x less often than before);
 *  - a file that shrinks (truncation) restarts from offset 0;
 *  - a dead producer plus drained file ends the tail;
 *  - cancellation-aware: a cancelled coroutine stops at the next poll.
 */
internal object OutputFileTailer {

    private const val ACTIVE_POLL_MS = 25L
    private const val MAX_IDLE_POLL_MS = 250L
    private const val CHUNK_BYTES = 16L * 1024

    /**
     * Follows [file] while [isAlive] reports true and delivers each decoded
     * chunk as it lands. After the producer dies the remaining bytes are still
     * drained before returning.
     */
    suspend fun tailChunks(file: File, isAlive: () -> Boolean, onChunk: suspend (String) -> Unit) {
        var offset = 0L
        var pollMs = ACTIVE_POLL_MS
        // Producers (native spawn, installers) may create the output file
        // moments after the process is reported alive — wait for it to appear
        // instead of failing the whole session.
        var input: RandomAccessFile? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (input == null) {
                    input = runCatching { RandomAccessFile(file, "r") }.getOrNull()
                    if (input == null) {
                        if (!isAlive()) break
                        delay(pollMs)
                        pollMs = minOf(pollMs * 2, MAX_IDLE_POLL_MS)
                        continue
                    }
                }
                val length = input!!.length()
                if (length < offset) {
                    // The file was truncated under us; restart from the top so
                    // the caller never silently misses content.
                    AppLog.w("OutputTailer", "${file.name} shrank; restarting the tail from offset 0")
                    offset = 0L
                }
                val available = length - offset
                if (available > 0L) {
                    input!!.seek(offset)
                    val bytes = ByteArray(minOf(available, CHUNK_BYTES).toInt())
                    val count = input!!.read(bytes)
                    if (count > 0) {
                        offset += count
                        pollMs = ACTIVE_POLL_MS
                        onChunk(bytes.decodeToString(0, count))
                    } else {
                        delay(pollMs)
                    }
                } else {
                    if (!isAlive()) break
                    delay(pollMs)
                    pollMs = minOf(pollMs * 2, MAX_IDLE_POLL_MS)
                }
            }
        } finally {
            runCatching { input?.close() }
        }
    }

    /**
     * Line-oriented variant: delivers every non-blank complete line while the
     * producer runs (and until the file drains after its death), then hands
     * any unterminated tail to [onTrailing] once — mirroring the previous
     * per-bridge "trailing output" handling.
     */
    suspend fun tailLines(
        file: File,
        isAlive: () -> Boolean,
        onLine: suspend (String) -> Unit,
        onTrailing: (suspend (String) -> Unit)? = null,
    ) {
        val pending = StringBuilder()
        tailChunks(file, isAlive) { chunk ->
            pending.append(chunk)
            var newline = pending.indexOf("\n")
            while (newline >= 0) {
                val line = pending.substring(0, newline).trimEnd('\r')
                pending.delete(0, newline + 1)
                if (line.isNotBlank()) onLine(line)
                newline = pending.indexOf("\n")
            }
        }
        onTrailing?.invoke(pending.toString())
    }
}
