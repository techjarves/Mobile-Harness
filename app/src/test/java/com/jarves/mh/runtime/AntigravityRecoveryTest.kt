package com.jarves.mh.runtime

import com.jarves.mh.model.RecoveryDecision
import com.jarves.mh.model.RuntimeRecoveryContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the Antigravity recovery helpers. No bridge instance is
 * constructed (its constructor needs an Android Context); every behavior under
 * test lives in a top-level, coroutine-free helper.
 */
class AntigravityRecoveryTest {

    @Test
    fun persistedHandleComesFromSavedConversationId() {
        assertEquals("conversation-42", antigravityPersistedHandle("conversation-42"))
    }

    @Test
    fun blankConversationIdYieldsNoHandle() {
        // A stale conversation is cleared with saveConversationId(projectId, "").
        // Without a handle the policy can never choose RESUME_PERSISTED, and
        // recover refuses to silently start a brand-new conversation instead.
        assertNull(antigravityPersistedHandle(null))
        assertNull(antigravityPersistedHandle(""))
        assertNull(antigravityPersistedHandle("   "))
    }

    @Test
    fun staleConversationFallsBackToManualAfterTools() {
        val decision = RuntimeRecoveryPolicy.decide(
            ANTIGRAVITY_RECOVERY_PROFILE,
            RuntimeRecoveryContext(
                sessionId = "session-1",
                inPlaceContinuationAvailable = false,
                persistedHandle = antigravityPersistedHandle(""),
                toolsExecuted = true,
                mutatingToolsExecuted = true,
                interruptedToolOutcomeKnown = false,
                checkpointAvailable = false,
                userStopped = false,
            ),
        )

        assertEquals(RecoveryDecision.MANUAL_RETRY_REQUIRED, decision)
        assertNotEquals(RecoveryDecision.RESUME_PERSISTED, decision)
    }

    @Test
    fun antigravityToolClassificationIsConservative() {
        listOf("Read", "List files", "Search").forEach {
            assertFalse("$it must be read-only", isAntigravityMutatingTool(it))
        }
        // Raw agy names and their prettified synonyms stay read-only even if one
        // ever reaches the classifier without going through the display mapping.
        listOf("view_file", "view file", "list_dir", "list dir", "grep_search", "search_files").forEach {
            assertFalse("$it must be read-only", isAntigravityMutatingTool(it))
        }
        listOf("Bash", "Write", "Wait", "Manage task", "run_command", "write_to_file", "unknown_tool").forEach {
            assertTrue("$it must be mutating", isAntigravityMutatingTool(it))
        }
        // Classification is case-insensitive.
        assertFalse(isAntigravityMutatingTool("read"))
        assertFalse(isAntigravityMutatingTool("SEARCH"))
        assertTrue(isAntigravityMutatingTool("bash"))
    }
}
