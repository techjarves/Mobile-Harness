package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectChat
import java.security.MessageDigest

/**
 * Pure size/retention policy for the SQLite conversation store (ISSUE-021).
 *
 * The pre-1.3.0 JSON files grew without any bound: every runtime event
 * rewrote the whole conversation array, and no cap existed on message count
 * or per-message size. This object centralizes every bound so the limits
 * are enforced in exactly one place and are unit-testable on the JVM.
 *
 * Precedent: project terminal history already keeps only the last
 * [MAX] entries (see MAX_PROJECT_TERMINAL_HISTORY in MainViewModel); the
 * same FIFO semantics are applied here to conversations and chat entries.
 *
 * All functions are total: they never throw and never reorder the input
 * (retention drops the oldest entries but preserves caller order).
 */
internal object ConversationPolicy {

    /** Hard cap on persisted messages per chat. In-memory UI state is unaffected. */
    const val MAX_MESSAGES_PER_CHAT = 2_000

    /** Single-message text cap. Only pathological messages (raw dumps) ever hit it. */
    const val MAX_MESSAGE_TEXT_CHARS = 100_000

    /** Cap on the serialized attachments / work-items JSON of one message. */
    const val MAX_ITEMS_JSON_CHARS = 100_000

    /** Hard cap on persisted chat entries per project (FIFO by updatedAtMillis). */
    const val MAX_CHATS_PER_PROJECT = 100

    private const val TRUNCATION_MARKER = "\n…[message truncated by Mobile Harness]"

    /** Truncates a message body to [MAX_MESSAGE_TEXT_CHARS] with an explicit marker. */
    fun capMessageText(text: String): String =
        if (text.length <= MAX_MESSAGE_TEXT_CHARS) text
        else text.take(MAX_MESSAGE_TEXT_CHARS).trimEnd() + TRUNCATION_MARKER

    /**
     * Keeps only the most recent [MAX_MESSAGES_PER_CHAT] messages. Oldest-first
     * entries are dropped, matching the terminal-history precedent.
     */
    fun applyMessageRetention(messages: List<ChatMessage>): List<ChatMessage> =
        if (messages.size <= MAX_MESSAGES_PER_CHAT) messages else messages.takeLast(MAX_MESSAGES_PER_CHAT)

    /**
     * Keeps only the [MAX_CHATS_PER_PROJECT] most recently updated chats while
     * preserving the caller's ordering (the UI shows newest-first already).
     */
    fun applyChatRetention(chats: List<ProjectChat>): List<ProjectChat> {
        if (chats.size <= MAX_CHATS_PER_PROJECT) return chats
        val keepIds = chats.sortedByDescending { it.updatedAtMillis }
            .take(MAX_CHATS_PER_PROJECT)
            .mapTo(mutableSetOf()) { it.id }
        return chats.filter { it.id in keepIds }
    }

    /**
     * Shrinks a serialized item list until its JSON fits [MAX_ITEMS_JSON_CHARS].
     * Items are dropped from the FRONT (oldest timeline entries go first) so the
     * result stays valid JSON — truncating the string itself would corrupt it.
     * [serialize] is invoked at most a few times (list sizes are modest).
     */
    fun <T> capItemsJson(items: List<T>, maxChars: Int = MAX_ITEMS_JSON_CHARS, serialize: (List<T>) -> String): String {
        var kept = items
        var json = serialize(kept)
        while (json.length > maxChars && kept.isNotEmpty()) {
            // Drop ~10% each round so long lists converge in a handful of iterations.
            kept = kept.drop((kept.size + 9) / 10)
            json = serialize(kept)
        }
        return json
    }

    /**
     * Stable content hash covering every persisted column of a message row:
     * position, flags, text, timestamp, worked time and both item JSONs.
     * SHA-256 truncated to 16 hex chars — collision odds are negligible and
     * the failure mode is one stale row re-written on the next cold save.
     */
    fun messageHash(seq: Int, message: ChatMessage, attachmentsJson: String, workItemsJson: String): String {
        val input = buildString(256) {
            append(seq).append('\u0000')
            append(message.id).append('\u0000')
            append(message.fromUser).append('\u0000')
            append(message.text).append('\u0000')
            append(message.createdAt).append('\u0000')
            append(message.workedMillis).append('\u0000')
            append(attachmentsJson).append('\u0000')
            append(workItemsJson)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Decides the minimal row write set for one save of a chat's transcript.
 *
 * The store keeps an in-memory map `messageId -> hash` per chat (warmed by
 * both loads and saves). On a warm cache only genuinely new/changed rows are
 * upserted and disappeared rows deleted — turning the old rewrite-the-whole-
 * file-per-runtime-event behavior into O(changed rows) writes per event.
 */
internal object TranscriptDiff {

    data class Plan(
        /** Rows to upsert, with their target sequence positions. */
        val toUpsert: List<Pair<Int, ChatMessage>>,
        /** Message ids present in the cache but absent from the incoming list. */
        val toDeleteIds: List<String>,
    )

    /**
     * @param cached id -> hash for the chat as last written/read, or null when
     *   the cache is cold (first touch this process): every row is written.
     * @param hashOf computes the policy hash for (seq, message); the store
     *   passes a closure that serializes items once and reuses the result.
     */
    fun plan(
        messages: List<ChatMessage>,
        cached: Map<String, String>?,
        hashOf: (Int, ChatMessage) -> String,
    ): Plan {
        if (cached == null || cached.isEmpty()) {
            return Plan(messages.withIndex().map { it.index to it.value }, emptyList())
        }
        val incomingIds = mutableSetOf<String>()
        val toUpsert = ArrayList<Pair<Int, ChatMessage>>(messages.size)
        messages.withIndex().forEach { (index, message) ->
            incomingIds.add(message.id)
            if (cached[message.id] != hashOf(index, message)) {
                toUpsert.add(index to message)
            }
        }
        val toDelete = cached.keys.filter { it !in incomingIds }
        return Plan(toUpsert, toDelete)
    }
}
