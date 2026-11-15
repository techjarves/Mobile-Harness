package com.jarves.mh.runtime

/**
 * Waits until [isAlive] is false, [quietMillis] pass without [length] changing, or
 * [timeoutMillis] passes, whichever comes first. [join] blocks for at most its argument and is
 * the only place time may pass for [now].
 *
 * Each call starts a fresh quiet window, so a caller that drains after a previous wait still
 * gives a live pump a chance to flush its final bytes.
 */
internal fun awaitOutputPumpQuiet(
    isAlive: () -> Boolean,
    length: () -> Long,
    now: () -> Long,
    join: (Long) -> Unit,
    timeoutMillis: Long,
    quietMillis: Long,
    pollMillis: Long,
) {
    val startNanos = now()
    var lastLength = length()
    var quietSinceNanos = startNanos
    val quietNanos = quietMillis * 1_000_000
    val deadlineNanos = startNanos + timeoutMillis * 1_000_000
    while (isAlive()) {
        val currentNanos = now()
        val currentLength = length()
        if (currentLength != lastLength) {
            lastLength = currentLength
            quietSinceNanos = currentNanos
        }
        if (currentNanos - quietSinceNanos >= quietNanos) return
        val remainingNanos = minOf(deadlineNanos, quietSinceNanos + quietNanos) - currentNanos
        if (remainingNanos <= 0) return
        val sliceMillis = (remainingNanos / 1_000_000).coerceAtLeast(1)
        join(minOf(pollMillis, sliceMillis))
    }
}
