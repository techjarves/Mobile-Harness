package com.jarves.mh.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the Claude recovery tool classification. No bridge instance is
 * constructed (its constructor needs an Android Context); the classifier is a
 * top-level, coroutine-free helper.
 */
class ClaudeRecoveryTest {

    @Test
    fun claudeToolClassificationIsConservative() {
        listOf("Read", "Glob", "Grep", "WebSearch", "WebFetch").forEach {
            assertFalse("$it must be read-only", isClaudeMutatingTool(it))
        }
        listOf("Bash", "Write", "Edit", "NotebookEdit", "Task", "Tool", "unknown_tool").forEach {
            assertTrue("$it must be mutating", isClaudeMutatingTool(it))
        }
        // Classification is case-insensitive.
        assertFalse(isClaudeMutatingTool("read"))
        assertFalse(isClaudeMutatingTool("webfetch"))
        assertTrue(isClaudeMutatingTool("bash"))
    }
}
