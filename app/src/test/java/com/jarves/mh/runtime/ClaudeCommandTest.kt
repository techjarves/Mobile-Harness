package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-007 (roadmap 3h): the headless Claude Code invocation must stay
 * byte-identical for normal sessions, and gain exactly `--resume <id>` —
 * and nothing else — for an interrupted-task resume.
 */
class ClaudeCommandTest {

    private fun command(resumeSessionId: String? = null): List<String> = claudePrintCommand(
        executable = "claude",
        model = "sonnet-4-6",
        prompt = "do the thing",
        resumeSessionId = resumeSessionId,
    )

    @Test
    fun `normal session keeps the established flag set`() {
        assertEquals(
            listOf(
                "claude",
                "--bare",
                "-p",
                "do the thing",
                "--output-format",
                "stream-json",
                "--include-partial-messages",
                "--verbose",
                "--model",
                "sonnet-4-6",
                "--max-turns",
                "25",
            ),
            command(),
        )
    }

    @Test
    fun `resume appends exactly the resume pair`() {
        val normal = command()
        val resumed = command(resumeSessionId = "session-abc")
        assertEquals(normal + listOf("--resume", "session-abc"), resumed)
    }

    @Test
    fun `blank resume ids are ignored`() {
        assertEquals(command(), command(resumeSessionId = ""))
        assertEquals(command(), command(resumeSessionId = "   "))
    }

    @Test
    fun `resume flag comes after the fixed arguments`() {
        val resumed = command(resumeSessionId = "id-1")
        assertEquals(listOf("--resume", "id-1"), resumed.takeLast(2))
        assertFalse(resumed.dropLast(2).contains("--resume"))
    }

    @Test
    fun `the prompt stays a single argument even when it contains newlines`() {
        val multiline = claudePrintCommand(
            executable = "claude",
            model = "m",
            prompt = "line one\nline two\n\nline three",
        )
        // The prompt must be one list element; the CLI receives it as one argv slot.
        assertEquals("line one\nline two\n\nline three", multiline[3])
    }
}
