package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalViewportTest {
    @Test
    fun `clamped enforces minimum`() {
        assertEquals(TerminalViewport(20, 4), TerminalViewport.clamped(0, 0))
        assertEquals(TerminalViewport(20, 4), TerminalViewport.clamped(-5, 3))
    }

    @Test
    fun `clamped enforces maximum`() {
        assertEquals(TerminalViewport(400, 200), TerminalViewport.clamped(999, 999))
    }

    @Test
    fun `valid values are preserved`() {
        assertEquals(TerminalViewport(80, 24), TerminalViewport.clamped(80, 24))
        assertEquals(TerminalViewport(120, 40), TerminalViewport.clamped(120, 40))
    }

    @Test
    fun `default viewport is 80 by 24`() {
        assertEquals(TerminalViewport(80, 24), TerminalViewport.Default)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `direct construction rejects out of range columns`() {
        TerminalViewport(10, 24)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `direct construction rejects out of range rows`() {
        TerminalViewport(80, 1)
    }
}
