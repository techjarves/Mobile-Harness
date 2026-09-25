package com.jarves.mh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ISSUE-007 (roadmap 3h) acceptance: a task interrupted by process death is
 * detectable at the next app start, and the journal degrades to a no-op on
 * every failure path instead of blocking the app. The interruption counter
 * must move exactly once per distinct task.
 */
class SessionJournalTest {

    private fun newJournal(): SessionJournal {
        val dir = Files.createTempDirectory("session-journal").toFile()
        return SessionJournal(dir)
    }

    private fun entry(
        agent: String = "claude-code",
        projectId: String = "p1",
        slug: String = "my-app",
        chatId: String? = "chat-1",
        request: String = "Fix the login bug",
        agentSessionId: String? = null,
        startedAt: Long = 1_700_000_000_000L,
    ) = JournalEntry(agent, projectId, slug, chatId, request, agentSessionId, startedAt)

    // ---- Codec: round trip -------------------------------------------------

    @Test
    fun `codec round trip preserves every field`() {
        val original = entry(agentSessionId = "sess-123", request = "Build the API")
        val decoded = SessionJournalCodec.decode(SessionJournalCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `codec round trip keeps nullables null`() {
        val original = entry(chatId = null, agentSessionId = null)
        val decoded = SessionJournalCodec.decode(SessionJournalCodec.encode(original))
        assertNull(decoded?.chatId)
        assertNull(decoded?.agentSessionId)
        assertEquals(original, decoded)
    }

    // ---- Codec: corrupt input must read as "no task in flight" -------------

    @Test
    fun `codec rejects null blank and non-json input`() {
        assertNull(SessionJournalCodec.decode(null))
        assertNull(SessionJournalCodec.decode(""))
        assertNull(SessionJournalCodec.decode("   "))
        assertNull(SessionJournalCodec.decode("not json at all"))
    }

    @Test
    fun `codec rejects missing or empty required fields`() {
        // No agent.
        assertNull(SessionJournalCodec.decode("{\"projectId\":\"p1\",\"projectSlug\":\"s\",\"startedAt\":123}"))
        // No projectId.
        assertNull(SessionJournalCodec.decode("{\"agent\":\"claude-code\",\"projectSlug\":\"s\",\"startedAt\":123}"))
        // No projectSlug.
        assertNull(SessionJournalCodec.decode("{\"agent\":\"claude-code\",\"projectId\":\"p1\",\"startedAt\":123}"))
        // Missing startedAt.
        assertNull(SessionJournalCodec.decode("{\"agent\":\"claude-code\",\"projectId\":\"p1\",\"projectSlug\":\"s\"}"))
        // Absurd startedAt.
        assertNull(
            SessionJournalCodec.decode(
                "{\"agent\":\"claude-code\",\"projectId\":\"p1\",\"projectSlug\":\"s\",\"startedAt\":0}",
            ),
        )
    }

    @Test
    fun `codec ignores unknown keys`() {
        // Forward compatibility: a future app version may add keys.
        val spliced = SessionJournalCodec.encode(entry())
            .replaceFirst("{", "{\"extra\":1,")
        val decoded = SessionJournalCodec.decode(spliced)
        assertNotNull(decoded)
        assertEquals("claude-code", decoded?.agentKind)
        assertEquals("p1", decoded?.projectId)
    }

    // ---- Journal file lifecycle --------------------------------------------

    @Test
    fun `begin then read returns the entry and clear makes it vanish`() {
        val journal = newJournal()
        assertNull(journal.read())

        journal.begin(entry())
        val read = journal.read()
        assertNotNull(read)
        assertEquals("claude-code", read?.agentKind)
        assertEquals("p1", read?.projectId)
        assertEquals("Fix the login bug", read?.request)

        journal.clear()
        assertNull(journal.read())
    }

    @Test
    fun `begin replaces the previous entry`() {
        val journal = newJournal()
        journal.begin(entry())
        journal.begin(entry(projectId = "p2", slug = "other", request = "Second task"))
        val read = journal.read()
        assertEquals("p2", read?.projectId)
        assertEquals("Second task", read?.request)
    }

    @Test
    fun `begin caps the stored request`() {
        val journal = newJournal()
        val huge = "x".repeat(SessionJournal.MAX_REQUEST_CHARS + 5_000)
        journal.begin(entry(request = huge))
        assertEquals(SessionJournal.MAX_REQUEST_CHARS, journal.read()?.request?.length)
    }

    @Test
    fun `clear is idempotent on a fresh journal`() {
        val journal = newJournal()
        journal.clear()
        journal.clear()
        assertNull(journal.read())
    }

    // ---- Native agent-session mirroring -------------------------------------

    @Test
    fun `recordAgentSession updates the current entry`() {
        val journal = newJournal()
        journal.begin(entry())
        journal.recordAgentSession("claude-session-42")
        assertEquals("claude-session-42", journal.read()?.agentSessionId)
    }

    @Test
    fun `recordAgentSession is a no-op without a live entry`() {
        val journal = newJournal()
        journal.recordAgentSession("claude-session-42")
        assertNull(journal.read())
    }

    @Test
    fun `recordAgentSession ignores blank and duplicate ids`() {
        val journal = newJournal()
        journal.begin(entry())
        journal.recordAgentSession("")
        journal.recordAgentSession(null)
        assertNull(journal.read()?.agentSessionId)
        journal.recordAgentSession("id-1")
        journal.recordAgentSession("id-1")
        assertEquals("id-1", journal.read()?.agentSessionId)
    }

    // ---- Interruption counter ------------------------------------------------

    @Test
    fun `counter counts each distinct task start exactly once`() {
        val journal = newJournal()
        assertEquals(0, journal.interruptionCount())

        assertEquals(1, journal.noteInterruptionObserved(startedAt = 100L))
        // Re-observing the SAME interruption (another app open) must not count again.
        assertEquals(1, journal.noteInterruptionObserved(startedAt = 100L))
        assertEquals(1, journal.interruptionCount())

        // A different task start counts.
        assertEquals(2, journal.noteInterruptionObserved(startedAt = 200L))
        assertEquals(2, journal.interruptionCount())
    }

    @Test
    fun `counter resets after a successful task`() {
        val journal = newJournal()
        journal.noteInterruptionObserved(startedAt = 100L)
        journal.noteInterruptionObserved(startedAt = 200L)
        journal.resetInterruptions()
        assertEquals(0, journal.interruptionCount())
        // After the reset the same task can be counted again — by design, only
        // success (not dismissal) clears the strikes.
        assertEquals(1, journal.noteInterruptionObserved(startedAt = 200L))
    }

    @Test
    fun `threshold constant matches the battery guidance rule`() {
        assertEquals(3, SessionJournal.INTERRUPTIONS_BEFORE_BATTERY_HINT)
    }

    // ---- Resume prompt ---------------------------------------------------------

    @Test
    fun `resume request keeps the original text and marks the interruption`() {
        val text = SessionJournalCodec.resumeRequestText(entry(request = "Fix the login bug"))
        assertTrue(text.startsWith("Fix the login bug"))
        assertTrue(text.contains("interrupted"))
    }

    @Test
    fun `resume request survives a blank stored request`() {
        val text = SessionJournalCodec.resumeRequestText(entry(request = "   "))
        assertTrue(text.isNotBlank())
        assertTrue(text.contains("Continue the interrupted task"))
    }

    // ---- Robustness ---------------------------------------------------------------

    @Test
    fun `journal reads a hand-corrupted active file as no task in flight`() {
        val dir = Files.createTempDirectory("session-journal-corrupt").toFile()
        val journal = SessionJournal(dir)
        journal.begin(entry())
        // Simulate a half-written file from a crash in the middle of a write.
        File(dir, "active.json").writeText("{\"agent\":\"claude-code\",\"projectId\":\"p1\"")
        assertNull(journal.read())
    }

    @Test
    fun `journal reads a corrupted counter file as zero`() {
        val dir = Files.createTempDirectory("session-journal-counter").toFile()
        val journal = SessionJournal(dir)
        File(dir, "interruptions").writeText("not-a-number\nalso-not")
        assertEquals(0, journal.interruptionCount())
        // The next observation simply starts counting again from one.
        assertEquals(1, journal.noteInterruptionObserved(startedAt = 5L))
    }
}
