package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalOutputTest {
    @Test
    fun `carriage return keeps the last progress frame`() {
        assertEquals("done\nnext", sanitizeTerminalOutput("10%\r50%\rdone\nnext"))
    }

    @Test
    fun `crlf becomes lf`() {
        assertEquals("line one\nline two", sanitizeTerminalOutput("line one\r\nline two"))
    }

    @Test
    fun `ansi colors are removed`() {
        assertEquals("ok", sanitizeTerminalOutput("\u001B[31mok\u001B[0m"))
    }
}
