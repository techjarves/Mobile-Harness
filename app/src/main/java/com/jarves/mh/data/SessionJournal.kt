package com.jarves.mh.data

import org.json.JSONObject
import java.io.File

/**
 * One in-flight agent task, as recorded on disk by [SessionJournal].
 *
 * `request` keeps the raw user text (not the attachment-augmented runtime prompt)
 * so a resumed task can be re-sent through the normal send-prompt pipeline, and
 * `agentSessionId` holds the agent's own native conversation identifier once it
 * has been observed (Claude Code `session_id`, Antigravity `conversationId`) so
 * the resume can continue the exact server-side context.
 */
data class JournalEntry(
    val agentKind: String,
    val projectId: String,
    val projectSlug: String,
    val chatId: String?,
    val request: String,
    val agentSessionId: String?,
    val startedAt: Long,
)

/**
 * Session journal (ISSUE-007, roadmap 3h): a tiny on-disk marker recording the
 * agent task currently in flight, so a process death can be detected and repaired.
 *
 * Lifecycle: [SessionJournal.begin] when a task starts →
 * [SessionJournal.recordAgentSession] when the agent reports its native
 * conversation id → [SessionJournal.clear] on any terminal event (completed,
 * failed or dismissed). A journal file that survives an app start therefore
 * means the OS killed the process mid-task, and the UI can offer a one-tap
 * resume ("Task interrupted — Resume / Dismiss").
 *
 * Failure contract (roadmap 2g): the journal is optional to read and safe to
 * ignore. A missing, unreadable or corrupt journal is indistinguishable from
 * "no task in flight" — it must never be able to break a session. Every
 * operation is best-effort and swallows its own IO failures.
 */
class SessionJournal(private val dir: File) {
    private val activeFile: File get() = File(dir, ACTIVE_FILE_NAME)
    private val counterFile: File get() = File(dir, COUNTER_FILE_NAME)

    /** Records the start of a task, replacing any previous entry. */
    @Synchronized
    fun begin(entry: JournalEntry) {
        runCatching {
            dir.mkdirs()
            writeAtomic(activeFile, SessionJournalCodec.encode(entry.copy(request = entry.request.take(MAX_REQUEST_CHARS))))
        }
    }

    /**
     * Stores the agent's native conversation id on the current entry. A no-op
     * when no entry exists (the task already finished or the journal was
     * cleared) and when the id is already current, so the frequent duplicate
     * reports from init/result events never touch the disk twice.
     */
    @Synchronized
    fun recordAgentSession(agentSessionId: String?) {
        if (agentSessionId.isNullOrBlank()) return
        runCatching {
            val entry = read() ?: return
            if (entry.agentSessionId == agentSessionId) return
            writeAtomic(activeFile, SessionJournalCodec.encode(entry.copy(agentSessionId = agentSessionId)))
        }
    }

    /** Current entry, or null when absent/corrupt/no task is in flight. */
    @Synchronized
    fun read(): JournalEntry? = runCatching { SessionJournalCodec.decode(activeFile.takeIf(File::isFile)?.readText()) }.getOrNull()

    /** Removes the active marker. Idempotent; a missing file is already clear. */
    @Synchronized
    fun clear() {
        runCatching { activeFile.delete() }
    }

    /**
     * Counts one observed interruption, at most once per distinct task start
     * (the same interrupted journal can be seen on several app opens; each
     * unique task must move the counter exactly once). Returns the updated
     * count so callers can react when the repeated-interruption threshold
     * is reached.
     */
    @Synchronized
    fun noteInterruptionObserved(startedAt: Long): Int {
        val state = readCounter()
        if (state != null && state.lastCountedStartedAt == startedAt) return state.count
        val updated = ((state?.count ?: 0) + 1).coerceAtMost(Int.MAX_VALUE - 1)
        runCatching {
            dir.mkdirs()
            writeAtomic(counterFile, "$updated\n$startedAt")
        }
        return updated
    }

    /** Interruptions observed so far; a fresh install or unreadable counter reads as zero. */
    @Synchronized
    fun interruptionCount(): Int = readCounter()?.count ?: 0

    /**
     * Clears the strike counter. Called when a task completes successfully:
     * only *repeated* interruptions — not a long-running healthy workload —
     * should ever trigger the battery-optimization guidance.
     */
    @Synchronized
    fun resetInterruptions() {
        runCatching { counterFile.delete() }
    }

    private fun readCounter(): InterruptionCounter? = runCatching {
        if (!counterFile.isFile) return null
        val parts = counterFile.readText().trim().split('\n')
        val count = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val last = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: -1L
        InterruptionCounter(count.coerceAtLeast(0), last)
    }.getOrNull()

    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // Fall back to a direct write; rename can fail across odd mount
            // layouts even though both files sit in the same directory.
            target.writeText(content)
            tmp.delete()
        }
    }

    private data class InterruptionCounter(val count: Int, val lastCountedStartedAt: Long)

    companion object {
        private const val ACTIVE_FILE_NAME = "active.json"
        private const val COUNTER_FILE_NAME = "interruptions"

        /** Hard cap for the stored request text; attachment-expanded prompts must not bloat the journal. */
        const val MAX_REQUEST_CHARS = 8_000

        /** After this many repeated interruptions the UI offers battery-optimization guidance (innovation 3). */
        const val INTERRUPTIONS_BEFORE_BATTERY_HINT = 3
    }
}

/**
 * Pure JSON (de)serialization for the journal. Kept separate from the file
 * handling so it can be unit-tested on the JVM without any filesystem or
 * Android dependency — the same pattern as ConversationCodec.
 */
object SessionJournalCodec {
    fun encode(entry: JournalEntry): String = JSONObject()
        .put(KEY_AGENT, entry.agentKind)
        .put(KEY_PROJECT_ID, entry.projectId)
        .put(KEY_PROJECT_SLUG, entry.projectSlug)
        .put(KEY_CHAT_ID, entry.chatId ?: "")
        .put(KEY_REQUEST, entry.request)
        .put(KEY_AGENT_SESSION_ID, entry.agentSessionId ?: "")
        .put(KEY_STARTED_AT, entry.startedAt)
        .toString()

    /**
     * Decodes one journal entry. Anything wrong — null input, invalid JSON,
     * missing or nonsensical required fields — yields null, never an exception:
     * the caller treats null as "no task in flight" and the journal degrades
     * to a no-op instead of blocking the app.
     */
    fun decode(raw: String?): JournalEntry? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val agent = json.optString(KEY_AGENT).trim()
            val projectId = json.optString(KEY_PROJECT_ID).trim()
            val projectSlug = json.optString(KEY_PROJECT_SLUG).trim()
            val startedAt = json.optLong(KEY_STARTED_AT, -1L)
            if (agent.isEmpty() || projectId.isEmpty() || projectSlug.isEmpty() || startedAt <= 0L) return null
            JournalEntry(
                agentKind = agent,
                projectId = projectId,
                projectSlug = projectSlug,
                chatId = json.optString(KEY_CHAT_ID).trim().takeUnless(String::isEmpty),
                request = json.optString(KEY_REQUEST),
                agentSessionId = json.optString(KEY_AGENT_SESSION_ID).trim().takeUnless(String::isEmpty),
                startedAt = startedAt,
            )
        }.getOrNull()
    }

    /**
     * The continuation prompt for a resumed task: the original request plus an
     * explicit instruction to pick up from the interruption. Agents with a
     * native conversation id already hold their own context; the others get
     * the full persisted transcript rebuilt by their bridge.
     */
    fun resumeRequestText(entry: JournalEntry): String {
        val request = entry.request.trim().takeUnless(String::isEmpty) ?: "Continue the interrupted task."
        return request +
            "\n\n[System] The app was interrupted while this task was running. " +
            "Continue it from the last completed step — earlier progress is preserved in this conversation and in the workspace."
    }

    private const val KEY_AGENT = "agent"
    private const val KEY_PROJECT_ID = "projectId"
    private const val KEY_PROJECT_SLUG = "projectSlug"
    private const val KEY_CHAT_ID = "chatId"
    private const val KEY_REQUEST = "request"
    private const val KEY_AGENT_SESSION_ID = "agentSessionId"
    private const val KEY_STARTED_AT = "startedAt"
}
