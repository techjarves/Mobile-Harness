package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputPumpWaitTest {
    private class FakePump(
        var alive: Boolean = true,
        var length: Long = 0L,
    ) {
        var nowNanos = 0L
        var joins = 0
        var onJoin: (FakePump) -> Unit = {}

        fun await(timeoutMillis: Long = 1_000, quietMillis: Long = 250, pollMillis: Long = 50) {
            awaitOutputPumpQuiet(
                isAlive = { alive },
                length = { length },
                now = { nowNanos },
                join = { millis ->
                    joins++
                    nowNanos += millis * 1_000_000
                    onJoin(this)
                },
                timeoutMillis = timeoutMillis,
                quietMillis = quietMillis,
                pollMillis = pollMillis,
            )
        }
    }

    @Test
    fun `returns immediately when the pump has exited`() {
        val pump = FakePump(alive = false)

        pump.await()

        assertEquals(0, pump.joins)
    }

    @Test
    fun `returns after a quiet window when no output arrives`() {
        val pump = FakePump(length = 10)

        pump.await(quietMillis = 250, pollMillis = 50)

        assertEquals(250_000_000L, pump.nowNanos)
    }

    @Test
    fun `second call waits for a fresh quiet window`() {
        val pump = FakePump(length = 10)

        pump.await(quietMillis = 250, pollMillis = 50)
        pump.await(quietMillis = 250, pollMillis = 50)

        assertEquals(500_000_000L, pump.nowNanos)
    }

    @Test
    fun `new output restarts the quiet window`() {
        val pump = FakePump(length = 10)
        pump.onJoin = { if (it.nowNanos == 50_000_000L) it.length = 20 }

        pump.await(quietMillis = 250, pollMillis = 50)

        assertEquals(300_000_000L, pump.nowNanos)
    }

    @Test
    fun `timeout stops a pump that keeps writing`() {
        val pump = FakePump(length = 0)
        pump.onJoin = { it.length += 1 }

        pump.await(timeoutMillis = 200, quietMillis = 250, pollMillis = 50)

        assertEquals(200_000_000L, pump.nowNanos)
    }

    @Test
    fun `pump exiting during a join stops the wait`() {
        val pump = FakePump(length = 10)
        pump.onJoin = { if (it.nowNanos >= 100_000_000L) it.alive = false }

        pump.await(timeoutMillis = 1_000, quietMillis = 250, pollMillis = 50)

        assertEquals(100_000_000L, pump.nowNanos)
    }
}
