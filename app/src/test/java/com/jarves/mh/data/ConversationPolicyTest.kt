package com.jarves.mh.data

import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectChat
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ISSUE-021 acceptance criterion: bounded conversation storage — no chat
 * record may grow without limit anymore (message count, message body size,
 * item JSON size, chat count per project), and the incremental diff must
 * shrink every runtime-event save to the changed rows only.
 */
class ConversationPolicyTest {

    private fun message(
        id: String,
        text: String = "body of $id",
        items: List<ActivityItem> = emptyList(),
        attachments: List<ChatAttachment> = emptyList(),
    ) = ChatMessage(
        id = id,
        fromUser = id.startsWith("u"),
        text = text,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        attachments = attachments,
        workItems = items,
        workedMillis = 0L,
    )

    // ---------------------------------------------------------------- //
    // Message caps
    // ---------------------------------------------------------------- //

    @Test
    fun `message text below the cap is untouched`() {
        assertEquals("hello", ConversationPolicy.capMessageText("hello"))
    }

    @Test
    fun `oversized message text is capped with an explicit marker`() {
        val huge = "x".repeat(ConversationPolicy.MAX_MESSAGE_TEXT_CHARS + 50_000)
        val capped = ConversationPolicy.capMessageText(huge)
        assertTrue(capped.length <= ConversationPolicy.MAX_MESSAGE_TEXT_CHARS + 100)
        assertTrue(capped.contains("[message truncated by Mobile Harness]"))
    }

    @Test
    fun `message retention keeps the newest entries in order`() {
        val messages = (0..ConversationPolicy.MAX_MESSAGES_PER_CHAT).map { message("m$it") }
        val kept = ConversationPolicy.applyMessageRetention(messages)
        assertEquals(ConversationPolicy.MAX_MESSAGES_PER_CHAT, kept.size)
        assertEquals("m1", kept.first().id)
        assertEquals("m${ConversationPolicy.MAX_MESSAGES_PER_CHAT}", kept.last().id)
    }

    @Test
    fun `short conversations pass through retention unchanged`() {
        val messages = listOf(message("a"), message("b"))
        assertEquals(messages, ConversationPolicy.applyMessageRetention(messages))
    }

    // ---------------------------------------------------------------- //
    // Chat caps
    // ---------------------------------------------------------------- //

    @Test
    fun `chat retention keeps the most recently updated and preserves order`() {
        val chats = (0 until 120).map { index ->
            ProjectChat(id = "c$index", updatedAtMillis = (120 - index).toLong()) // c0 newest
        }
        val kept = ConversationPolicy.applyChatRetention(chats)
        assertEquals(ConversationPolicy.MAX_CHATS_PER_PROJECT, kept.size)
        // Caller order preserved, oldest (highest index) dropped.
        assertEquals(chats.take(100), kept)
    }

    // ---------------------------------------------------------------- //
    // Item JSON caps
    // ---------------------------------------------------------------- //

    private val serializer: (List<String>) -> String = { list ->
        JSONArray().apply { list.forEach { put(it) } }.toString()
    }

    @Test
    fun `item json under the cap is serialized whole`() {
        val json = ConversationPolicy.capItemsJson(listOf("a", "b"), maxChars = 10_000, serialize = serializer)
        assertEquals("""["a","b"]""", json)
    }

    @Test
    fun `oversized item json drops oldest entries and stays valid json`() {
        val items = (0 until 2_000).map { "item-$it-" + "x".repeat(100) } // ~240KB total
        val json = ConversationPolicy.capItemsJson(items, maxChars = 4_096, serialize = serializer)
        assertTrue("expected <= 4096 chars, got ${json.length}", json.length <= 4_096)
        val parsed = JSONArray(json) // must still parse — truncating the string would corrupt it
        assertTrue(parsed.length() > 0)
        // Entries are dropped from the FRONT: the newest item must survive.
        val last = parsed.getString(parsed.length() - 1)
        assertTrue(last.startsWith("item-1999"))
    }

    @Test
    fun `a single oversized item degrades to an empty array instead of looping`() {
        val json = ConversationPolicy.capItemsJson(listOf("x".repeat(100_000)), maxChars = 100, serialize = serializer)
        assertEquals("[]", json)
    }

    // ---------------------------------------------------------------- //
    // Hashing + diff
    // ---------------------------------------------------------------- //

    private fun hashOf(): (Int, ChatMessage) -> String = { seq, m ->
        ConversationPolicy.messageHash(
            seq,
            m,
            ConversationCodec.attachmentsToJson(m.attachments),
            ConversationCodec.workItemsToJson(m.workItems),
        )
    }

    @Test
    fun `hash is stable and sensitive to every persisted field`() {
        val m = message("m1")
        val base = ConversationPolicy.messageHash(0, m, "[]", "[]")
        assertEquals(base, ConversationPolicy.messageHash(0, m, "[]", "[]"))
        assertNotEqualsCompat(base, ConversationPolicy.messageHash(1, m, "[]", "[]")) // seq
        assertNotEqualsCompat(base, ConversationPolicy.messageHash(0, m.copy(text = "other"), "[]", "[]"))
        assertNotEqualsCompat(base, ConversationPolicy.messageHash(0, m, """[{"id":"a"}]""", "[]"))
        assertNotEqualsCompat(base, ConversationPolicy.messageHash(0, m.copy(workedMillis = 5), "[]", "[]"))
    }

    @Test
    fun `cold cache writes every row`() {
        val messages = listOf(message("a"), message("b"))
        val plan = TranscriptDiff.plan(messages, null, hashOf())
        assertEquals(2, plan.toUpsert.size)
        assertTrue(plan.toDeleteIds.isEmpty())
    }

    @Test
    fun `unchanged warm cache writes nothing`() {
        val messages = listOf(message("a"), message("b"))
        val hashes = messages.withIndex().associate { (i, m) -> m.id to hashOf().invoke(i, m) }
        val plan = TranscriptDiff.plan(messages, hashes, hashOf())
        assertTrue(plan.toUpsert.isEmpty())
        assertTrue(plan.toDeleteIds.isEmpty())
    }

    @Test
    fun `appending one message writes exactly that row`() {
        val base = listOf(message("a"), message("b"))
        val hashes = base.withIndex().associate { (i, m) -> m.id to hashOf().invoke(i, m) }
        val grown = base + message("c")
        val plan = TranscriptDiff.plan(grown, hashes, hashOf())
        assertEquals(listOf("c"), plan.toUpsert.map { it.second.id })
        assertTrue(plan.toDeleteIds.isEmpty())
    }

    @Test
    fun `editing a middle message rewrites only that row`() {
        val base = listOf(message("a"), message("b"), message("c"))
        val hashes = base.withIndex().associate { (i, m) -> m.id to hashOf().invoke(i, m) }
        val edited = listOf(message("a"), message("b", text = "edited"), message("c"))
        val plan = TranscriptDiff.plan(edited, hashes, hashOf())
        assertEquals(listOf("b"), plan.toUpsert.map { it.second.id })
        assertTrue(plan.toDeleteIds.isEmpty())
    }

    @Test
    fun `removing the head message deletes it and rewrites the shifted tail`() {
        val base = listOf(message("a"), message("b"))
        val hashes = base.withIndex().associate { (i, m) -> m.id to hashOf().invoke(i, m) }
        val shrunk = listOf(message("b"))
        val plan = TranscriptDiff.plan(shrunk, hashes, hashOf())
        // "a" disappears; "b" moved from seq 1 to seq 0 and must be rewritten
        // (seq is part of the hash so on-disk ordering can never go stale).
        assertEquals(listOf("b"), plan.toUpsert.map { it.second.id })
        assertEquals(listOf("a"), plan.toDeleteIds)
    }

    @Test
    fun `insertion ahead of existing rows rewrites the shifted tail`() {
        val base = listOf(message("a"), message("b"))
        val hashes = base.withIndex().associate { (i, m) -> m.id to hashOf().invoke(i, m) }
        val shifted = listOf(message("a"), message("mid"), message("b"))
        val plan = TranscriptDiff.plan(shifted, hashes, hashOf())
        // "mid" is new; "b" moved from seq 1 to seq 2 and must be rewritten.
        assertEquals(setOf("mid", "b"), plan.toUpsert.map { it.second.id }.toSet())
        assertTrue(plan.toDeleteIds.isEmpty())
    }

    private fun assertNotEqualsCompat(expected: String, actual: String) {
        assertTrue("expected hashes to differ: $expected vs $actual", expected != actual)
    }
}
