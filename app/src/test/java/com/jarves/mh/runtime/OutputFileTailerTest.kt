package com.jarves.mh.runtime

import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ISSUE-013: the single shared output-file tail must reproduce the exact
 * contract of the six inline loops it replaced — complete lines while the
 * producer runs, the unterminated tail at the end, blank lines dropped,
 * CRLF trimmed and truncation survived.
 */
class OutputFileTailerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `delivers complete lines and the trailing partial`() = runBlocking {
        val file = temp.newFile("out.log")
        val writer = thread {
            file.appendText("line-one\n\r\nline-two\r\n")
            Thread.sleep(150)
            file.appendText("partial-without-newline")
        }
        val lines = mutableListOf<String>()
        var trailing: String? = null

        OutputFileTailer.tailLines(
            file,
            isAlive = writer::isAlive,
            onLine = { lines += it },
            onTrailing = { trailing = it },
        )

        // The blank line is dropped and CRLF is trimmed, like the old loops.
        assertEquals(listOf("line-one", "line-two"), lines)
        assertEquals("partial-without-newline", trailing)
    }

    @Test
    fun `drains everything written just before the producer died`() = runBlocking {
        val file = temp.newFile("dead.log")
        file.appendText("already-here\n")

        val lines = mutableListOf<String>()
        OutputFileTailer.tailLines(
            file,
            isAlive = { false },
            onLine = { lines += it },
        )
        assertEquals(listOf("already-here"), lines)
    }

    @Test
    fun `a truncated file restarts from offset zero`() = runBlocking {
        val file = temp.newFile("truncated.log")
        val writer = thread {
            file.appendText("before-truncation\n")
            Thread.sleep(150)
            file.writeText("after-truncation\n")
        }
        val lines = mutableListOf<String>()

        OutputFileTailer.tailLines(file, isAlive = writer::isAlive, onLine = { lines += it })

        assertEquals(listOf("before-truncation", "after-truncation"), lines)
    }

    @Test
    fun `chunk mode delivers raw chunks without line splitting`() = runBlocking {
        val file = temp.newFile("raw.log")
        val writer = thread {
            file.appendText("abc")
            Thread.sleep(120)
            file.appendText("def\nxyz")
        }
        val received = StringBuilder()

        OutputFileTailer.tailChunks(file, isAlive = writer::isAlive) { chunk ->
            received.append(chunk)
        }

        assertEquals("abcdef\nxyz", received.toString())
    }

    @Test
    fun `waits for a file the producer has not written yet`() = runBlocking {
        val file = File(temp.root, "late.log")
        val writer = thread {
            Thread.sleep(150)
            file.writeText("late-line\n")
        }
        val lines = mutableListOf<String>()

        OutputFileTailer.tailLines(file, isAlive = writer::isAlive, onLine = { lines += it })

        assertEquals(listOf("late-line"), lines)
    }
}
