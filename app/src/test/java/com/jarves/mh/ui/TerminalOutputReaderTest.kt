package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TerminalOutputReaderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `reads text then returns null`() {
        val file = temp.newFile("output.log").apply { writeText("hello\n") }

        val first = readTerminalOutputChunk(file, 0)

        assertEquals("hello\n", first?.text)
        assertEquals(file.length(), first?.nextOffset)
        assertNull(readTerminalOutputChunk(file, first!!.nextOffset))
    }

    @Test
    fun `caps each read at maxBytes`() {
        val file = temp.newFile("large.log").apply { writeText("x".repeat(20 * 1024)) }

        val first = readTerminalOutputChunk(file, 0, maxBytes = 16 * 1024)

        assertEquals(16 * 1024L, first?.nextOffset)
        assertEquals(16 * 1024, first?.text?.length)
        val second = readTerminalOutputChunk(file, first!!.nextOffset, maxBytes = 16 * 1024)
        assertEquals(4 * 1024, second?.text?.length)
        assertEquals(file.length(), second?.nextOffset)
    }

    @Test
    fun `offset past end returns null`() {
        val file = temp.newFile("short.log").apply { writeText("hi") }

        assertNull(readTerminalOutputChunk(file, file.length() + 1))
    }

    @Test
    fun `empty file returns null`() {
        val file = temp.newFile("empty.log")

        assertNull(readTerminalOutputChunk(file, 0))
    }

    @Test
    fun `missing file returns null until the pump creates it`() {
        val file = File(temp.root, "not-created-yet.log")

        assertNull(readTerminalOutputChunk(file, 0))
    }
}
