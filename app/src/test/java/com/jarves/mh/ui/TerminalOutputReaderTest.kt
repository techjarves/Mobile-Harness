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

    @Test
    fun `drain stops at the total byte cap`() {
        val file = temp.newFile("capped.log").apply { writeText("x".repeat(250)) }
        val collected = StringBuilder()

        val offset = drainTerminalOutput(file, 0, maxBytes = 16, maxTotalBytes = 40) {
            collected.append(it)
        }

        assertEquals(40, collected.length)
        assertEquals(40L, offset)
    }

    @Test
    fun `drain recovers bytes written after the last poll`() {
        val file = temp.newFile("drain.log").apply { writeText("start\n") }
        val first = readTerminalOutputChunk(file, 0)!!
        val collected = StringBuilder(first.text)

        // The pump writes the tail and the cwd marker after the poll loop saw the first chunk.
        file.appendText("tail\n__POCKETDEV_CWD__/workspace/project\n")

        val offset = drainTerminalOutput(file, first.nextOffset) { collected.append(it) }

        assertEquals(
            "start\ntail\n__POCKETDEV_CWD__/workspace/project\n",
            collected.toString(),
        )
        assertEquals(file.length(), offset)
        assertNull(readTerminalOutputChunk(file, offset))
    }
}
