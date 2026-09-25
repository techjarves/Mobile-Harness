package com.jarves.mh.data

import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Pure serialization codec for the SQLite conversation store (ISSUE-021).
 *
 * Owns every conversion between [ChatMessage] parts and the JSON strings
 * stored in the `messages` table columns, plus the legacy one-array-per-chat
 * JSON format used by pre-1.3.0 builds (`filesDir/chats/<projectId>/<chatId>.json`).
 *
 * Kept free of Android imports so it runs under plain JVM unit tests
 * (org.json is provided by the Android SDK in main sources and by the
 * `org.json:json` test dependency in unit tests — the established pattern
 * in this project, see AgentPermissionsTest).
 *
 * Robustness contract (mirrors the previous AppPreferences parser):
 * any malformed input yields an empty list / a blank-safe default instead
 * of an exception, because a single corrupt message must never lose the
 * rest of a conversation.
 */
internal object ConversationCodec {

    // ------------------------------------------------------------------ //
    // Chat message attachments
    // ------------------------------------------------------------------ //

    fun attachmentsToJson(attachments: List<ChatAttachment>): String {
        if (attachments.isEmpty()) return "[]"
        val arr = JSONArray()
        attachments.forEach { attachment ->
            arr.put(
                JSONObject()
                    .put("id", attachment.id)
                    .put("displayName", attachment.displayName)
                    .put("relativePath", attachment.relativePath)
                    .put("mimeType", attachment.mimeType)
                    .put("sizeBytes", attachment.sizeBytes),
            )
        }
        return arr.toString()
    }

    fun attachmentsFromJson(raw: String): List<ChatAttachment> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { index ->
                runCatching {
                    arr.getJSONObject(index).let { attachment ->
                        ChatAttachment(
                            id = attachment.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            displayName = attachment.getString("displayName"),
                            relativePath = attachment.getString("relativePath"),
                            mimeType = attachment.optString("mimeType", "application/octet-stream"),
                            sizeBytes = attachment.optLong("sizeBytes", 0L),
                        )
                    }
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ //
    // Work items (the activity timeline shown inside a message bubble)
    // ------------------------------------------------------------------ //

    fun workItemsToJson(items: List<ActivityItem>): String {
        if (items.isEmpty()) return "[]"
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("title", item.title)
                    .put("detail", item.detail)
                    .put("isComplete", item.isComplete)
                    .put("isCommand", item.isCommand),
            )
        }
        return arr.toString()
    }

    fun workItemsFromJson(raw: String): List<ActivityItem> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { index ->
                runCatching {
                    arr.getJSONObject(index).let { item ->
                        ActivityItem(
                            title = item.optString("title"),
                            detail = item.optString("detail"),
                            isComplete = item.optBoolean("isComplete", true),
                            isCommand = item.optBoolean("isCommand", false),
                        )
                    }
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ //
    // Legacy per-chat JSON array (pre-1.3.0 on-disk format) — import only
    // ------------------------------------------------------------------ //

    /**
     * Parses the full legacy `<chatId>.json` array. Used once per chat by the
     * verified migration path in AppPreferences; never used for regular saves.
     */
    fun legacyMessagesFromJson(raw: String): List<ChatMessage> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ChatMessage(
                    id = obj.getString("id"),
                    fromUser = obj.getBoolean("fromUser"),
                    text = obj.getString("text"),
                    createdAt = runCatching { Instant.parse(obj.getString("createdAt")) }
                        .getOrDefault(Instant.now()),
                    attachments = obj.optJSONArray("attachments")
                        ?.let { attachments -> attachmentsFromHolder(attachments) }
                        .orEmpty(),
                    workedMillis = obj.optLong("workedMillis", 0L),
                    workItems = obj.optJSONArray("workItems")
                        ?.let { workItems -> workItemsFromHolder(workItems) }
                        .orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun attachmentsFromHolder(attachments: JSONArray): List<ChatAttachment> =
        attachmentsFromJson(attachments.toString())

    private fun workItemsFromHolder(workItems: JSONArray): List<ActivityItem> =
        workItemsFromJson(workItems.toString())

    // ------------------------------------------------------------------ //
    // Legacy chat index (`index.json`) — import only
    // ------------------------------------------------------------------ //

    fun legacyChatIndexFromJson(raw: String): List<com.jarves.mh.model.ProjectChat> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                com.jarves.mh.model.ProjectChat(
                    id = obj.getString("id"),
                    title = obj.optString("title", "Chat"),
                    createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                    updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis()),
                )
            }
        }.getOrDefault(emptyList())
    }
}
