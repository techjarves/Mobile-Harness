package com.jarves.mh.data

import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ISSUE-021: the SQLite store persists attachments and work items as JSON
 * columns and imports the pre-1.3.0 per-chat JSON files. The codec must
 * round-trip both without loss and never throw on malformed input — a single
 * corrupt message must not lose the rest of a conversation (the robustness
 * contract the previous AppPreferences parser guaranteed).
 */
class ConversationCodecTest {

    // ---------------------------------------------------------------- //
    // Attachments
    // ---------------------------------------------------------------- //

    @Test
    fun `attachments round trip`() {
        val attachments = listOf(
            ChatAttachment(
                id = "att-1",
                displayName = "screenshot.png",
                relativePath = "uploads/screenshot.png",
                mimeType = "image/png",
                sizeBytes = 12_345L,
            ),
            ChatAttachment(id = "att-2", displayName = "notes.txt", relativePath = "uploads/notes.txt", mimeType = "text/plain", sizeBytes = 42L),
        )
        val json = ConversationCodec.attachmentsToJson(attachments)
        val parsed = ConversationCodec.attachmentsFromJson(json)
        assertEquals(attachments[0], parsed[0])
        assertEquals(attachments[1], parsed[1])
    }

    @Test
    fun `missing mimeType in stored json falls back to the documented default`() {
        val json = """[{"id":"a","displayName":"log","relativePath":"uploads/log"}]"""
        val parsed = ConversationCodec.attachmentsFromJson(json)
        assertEquals(1, parsed.size)
        assertEquals("application/octet-stream", parsed[0].mimeType)
        assertEquals(0L, parsed[0].sizeBytes) // optLong default for a missing field
    }

    @Test
    fun `empty attachments serialize to a canonical empty array`() {
        assertEquals("[]", ConversationCodec.attachmentsToJson(emptyList()))
        assertTrue(ConversationCodec.attachmentsFromJson("[]").isEmpty())
        assertTrue(ConversationCodec.attachmentsFromJson("").isEmpty())
    }

    @Test
    fun `malformed attachments yield an empty list not an exception`() {
        assertTrue(ConversationCodec.attachmentsFromJson("{oops").isEmpty())
        assertTrue(ConversationCodec.attachmentsFromJson("""[{"noDisplayName":true}]""").isEmpty())
    }

    // ---------------------------------------------------------------- //
    // Work items
    // ---------------------------------------------------------------- //

    @Test
    fun `work items round trip with defaults`() {
        val items = listOf(
            ActivityItem(title = "Running tests", detail = "pytest -q", isComplete = false, isCommand = true),
            ActivityItem(title = "Done", detail = "3 passed"),
        )
        val parsed = ConversationCodec.workItemsFromJson(ConversationCodec.workItemsToJson(items))
        assertEquals(items, parsed)
    }

    @Test
    fun `malformed work items are dropped individually`() {
        val json = """[
            {"title":"ok","detail":"fine"},
            {"title":"also ok"}
        ]"""
        val parsed = ConversationCodec.workItemsFromJson(json)
        assertEquals(2, parsed.size)
        assertTrue(ConversationCodec.workItemsFromJson("not json at all").isEmpty())
    }

    // ---------------------------------------------------------------- //
    // Legacy per-chat array (pre-1.3.0 files)
    // ---------------------------------------------------------------- //

    @Test
    fun `legacy message array parses fully`() {
        val legacy = """
            [
              {
                "id": "m1",
                "fromUser": true,
                "text": "fix the login bug",
                "createdAt": "2026-09-01T10:15:30Z",
                "attachments": [
                  {"id":"a1","displayName":"log.txt","relativePath":"uploads/log.txt","mimeType":"text/plain","sizeBytes":99}
                ],
                "workedMillis": 42000,
                "workItems": [
                  {"title":"Read file","detail":"src/login.kt","isComplete":true,"isCommand":false}
                ]
              },
              {
                "id": "m2",
                "fromUser": false,
                "text": "patched it",
                "createdAt": "2026-09-01T10:16:00Z"
              }
            ]
        """.trimIndent()
        val parsed = ConversationCodec.legacyMessagesFromJson(legacy)
        assertEquals(2, parsed.size)
        assertEquals("m1", parsed[0].id)
        assertTrue(parsed[0].fromUser)
        assertEquals(Instant.parse("2026-09-01T10:15:30Z"), parsed[0].createdAt)
        assertEquals(1, parsed[0].attachments.size)
        assertEquals("uploads/log.txt", parsed[0].attachments[0].relativePath)
        assertEquals(42_000L, parsed[0].workedMillis)
        assertEquals(1, parsed[0].workItems.size)
        assertEquals(false, parsed[0].workItems[0].isCommand)
        // Second message: optional fields default.
        assertTrue(parsed[1].attachments.isEmpty())
        assertTrue(parsed[1].workItems.isEmpty())
        assertEquals(0L, parsed[1].workedMillis)
    }

    @Test
    fun `legacy array with corrupt entries survives via defaults`() {
        val legacy = """[{"id":"ok","fromUser":true,"text":"fine","createdAt":"not-a-date"}]"""
        val parsed = ConversationCodec.legacyMessagesFromJson(legacy)
        assertEquals(1, parsed.size)
        // Unparseable timestamp degrades to now() instead of failing the chat.
        assertTrue(parsed[0].createdAt.epochSecond > 0)
    }

    @Test
    fun `garbage legacy files parse to empty without throwing`() {
        assertTrue(ConversationCodec.legacyMessagesFromJson("").isEmpty())
        assertTrue(ConversationCodec.legacyMessagesFromJson("}{").isEmpty())
        assertTrue(ConversationCodec.legacyMessagesFromJson("""[{"noId":true}]""").isEmpty())
    }

    // ---------------------------------------------------------------- //
    // Legacy chat index (index.json)
    // ---------------------------------------------------------------- //

    @Test
    fun `legacy chat index parses and orders by recency at the caller`() {
        val index = """
            [
              {"id":"c2","title":"Second","createdAtMillis":200,"updatedAtMillis":900},
              {"id":"c1","title":"First","createdAtMillis":100,"updatedAtMillis":1000}
            ]
        """.trimIndent()
        val parsed = ConversationCodec.legacyChatIndexFromJson(index)
        assertEquals(2, parsed.size)
        assertEquals("c1", parsed.maxByOrNull { it.updatedAtMillis }!!.id)
    }

    @Test
    fun `malformed chat index yields empty list`() {
        assertTrue(ConversationCodec.legacyChatIndexFromJson("nope").isEmpty())
        assertTrue(ConversationCodec.legacyChatIndexFromJson("").isEmpty())
    }
}
