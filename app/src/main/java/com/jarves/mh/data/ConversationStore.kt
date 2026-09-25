package com.jarves.mh.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.runtime.AppLog
import java.io.File
import java.time.Instant

/**
 * SQLite-backed conversation persistence (ISSUE-021, roadmap 3c).
 *
 * Replaces the JSON files under `filesDir/chats/` that were rewritten in
 * full on EVERY runtime event (tool transition, reasoning tick, completion)
 * with a row-oriented store that writes only new/changed messages per save.
 *
 * Properties inherited from the previous storage and kept unchanged:
 *  - Same at-rest protection: a private database inside filesDir, covered by
 *    Android File-Based Encryption exactly like the old JSON files.
 *  - Same public API surface (AppPreferences delegates here), so call sites
 *    in MainViewModel are untouched.
 *
 * New properties:
 *  - Bounded storage: per-chat message cap, per-message text/item caps and a
 *    per-project chat cap (ConversationPolicy), enforced inside the store so
 *    no caller can bypass them.
 *  - Incremental writes: a per-chat hash cache (warmed by both loads and
 *    saves) lets TranscriptDiff shrink each save to the changed rows.
 *  - Single connection: process-wide singleton — AppPreferences is
 *    instantiated by both MainViewModel and RuntimeSetupService, and the
 *    diff cache must not fork.
 *
 * The class is a thin coordinator on purpose: all serialization lives in
 * ConversationCodec and all limits/decisions in ConversationPolicy, both of
 * which run under plain JVM unit tests. Only this file touches
 * android.database.sqlite.
 */
internal class ConversationStore private constructor(
    appContext: Context,
    dbFile: File,
) {
    private val helper = object : SQLiteOpenHelper(appContext, dbFile.absolutePath, null, SCHEMA_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE chats (
                    project_id TEXT NOT NULL,
                    chat_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL,
                    updated_at_millis INTEGER NOT NULL,
                    PRIMARY KEY (project_id, chat_id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE messages (
                    project_id TEXT NOT NULL,
                    chat_id TEXT NOT NULL,
                    message_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    from_user INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    worked_millis INTEGER NOT NULL,
                    attachments_json TEXT NOT NULL,
                    work_items_json TEXT NOT NULL,
                    PRIMARY KEY (project_id, chat_id, message_id)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_messages_chat_order ON messages (project_id, chat_id, seq)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit // v1: no prior schema

        override fun onConfigure(db: SQLiteDatabase) {
            // WAL is the platform default for targetSdk >= P (28). The explicit
            // setter was removed from SDK 36; its replacement is only public
            // from API 35, so belt-and-braces enablement is guarded.
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                runCatching { db.enableWriteAheadLogging() }
            }
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    /** messageId -> policy hash, per (projectId, chatId); warmed by loads and saves. */
    private val hashCache = HashMap<String, HashMap<String, String>>()

    // ------------------------------------------------------------------ //
    // Chats
    // ------------------------------------------------------------------ //

    @Synchronized
    fun saveChats(projectId: String, chats: List<ProjectChat>) {
        val capped = ConversationPolicy.applyChatRetention(chats)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val keepIds = capped.mapTo(mutableSetOf()) { it.id }
            // Drop chat rows the retention policy removed, plus their messages.
            loadChatsLocked(db, projectId).filter { it.id !in keepIds }.forEach { chat ->
                db.delete(TABLE_CHATS, WHERE_PROJECT_CHAT, arrayOf(projectId, chat.id))
                db.delete(TABLE_MESSAGES, WHERE_PROJECT_CHAT, arrayOf(projectId, chat.id))
                hashCache.remove(cacheKey(projectId, chat.id))
            }
            capped.forEach { chat ->
                db.insertWithOnConflict(
                    TABLE_CHATS,
                    null,
                    ContentValues().apply {
                        put("project_id", projectId)
                        put("chat_id", chat.id)
                        put("title", chat.title)
                        put("created_at_millis", chat.createdAtMillis)
                        put("updated_at_millis", chat.updatedAtMillis)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun loadChats(projectId: String): List<ProjectChat> =
        loadChatsLocked(helper.readableDatabase, projectId)

    private fun loadChatsLocked(db: SQLiteDatabase, projectId: String): List<ProjectChat> {
        val chats = mutableListOf<ProjectChat>()
        db.query(
            TABLE_CHATS,
            arrayOf("chat_id", "title", "created_at_millis", "updated_at_millis"),
            "project_id = ?",
            arrayOf(projectId),
            null,
            null,
            "updated_at_millis DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                chats.add(
                    ProjectChat(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        createdAtMillis = cursor.getLong(2),
                        updatedAtMillis = cursor.getLong(3),
                    ),
                )
            }
        }
        return chats
    }

    // ------------------------------------------------------------------ //
    // Messages
    // ------------------------------------------------------------------ //

    @Synchronized
    fun saveMessages(projectId: String, chatId: String, messages: List<ChatMessage>) {
        val capped = ConversationPolicy.applyMessageRetention(messages)
        val key = cacheKey(projectId, chatId)

        // Serialize + hash every row once; the diff reuses these results.
        data class Row(
            val seq: Int,
            val message: ChatMessage,
            val attachmentsJson: String,
            val workItemsJson: String,
            val hash: String,
        )

        val rows = capped.withIndex().map { (index, message) ->
            val attachmentsJson = ConversationPolicy.capItemsJson(message.attachments) { list ->
                ConversationCodec.attachmentsToJson(list)
            }
            val workItemsJson = ConversationPolicy.capItemsJson(message.workItems) { list ->
                ConversationCodec.workItemsToJson(list)
            }
            Row(
                seq = index,
                message = message,
                attachmentsJson = attachmentsJson,
                workItemsJson = workItemsJson,
                hash = ConversationPolicy.messageHash(index, message, attachmentsJson, workItemsJson),
            )
        }

        val plan = TranscriptDiff.plan(
            capped,
            hashCache[key],
        ) { seq, message ->
            rows.getOrNull(seq)?.takeIf { it.message.id == message.id }?.hash
                ?: ConversationPolicy.messageHash(
                    seq,
                    message,
                    ConversationCodec.attachmentsToJson(message.attachments),
                    ConversationCodec.workItemsToJson(message.workItems),
                )
        }

        if (plan.toUpsert.isEmpty() && plan.toDeleteIds.isEmpty()) return

        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            plan.toUpsert.forEach { (seq, message) ->
                val row = rows.getOrNull(seq)?.takeIf { it.message.id == message.id }
                    ?: Row(
                        seq,
                        message,
                        ConversationCodec.attachmentsToJson(message.attachments),
                        ConversationCodec.workItemsToJson(message.workItems),
                        "",
                    )
                db.insertWithOnConflict(
                    TABLE_MESSAGES,
                    null,
                    ContentValues().apply {
                        put("project_id", projectId)
                        put("chat_id", chatId)
                        put("message_id", message.id)
                        put("seq", seq)
                        put("from_user", if (message.fromUser) 1 else 0)
                        put("text", ConversationPolicy.capMessageText(message.text))
                        put("created_at", message.createdAt.toString())
                        put("worked_millis", message.workedMillis)
                        put("attachments_json", row.attachmentsJson)
                        put("work_items_json", row.workItemsJson)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            plan.toDeleteIds.forEach { messageId ->
                db.delete(TABLE_MESSAGES, WHERE_PROJECT_CHAT_MESSAGE, arrayOf(projectId, chatId, messageId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // The cache now mirrors exactly what was just written.
        hashCache[key] = rows.associateTo(HashMap()) { it.message.id to it.hash }
    }

    @Synchronized
    fun loadMessages(projectId: String, chatId: String): List<ChatMessage> {
        val key = cacheKey(projectId, chatId)
        val messages = mutableListOf<ChatMessage>()
        val hashes = HashMap<String, String>()
        helper.readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf("message_id", "seq", "from_user", "text", "created_at", "worked_millis", "attachments_json", "work_items_json"),
            "project_id = ? AND chat_id = ?",
            arrayOf(projectId, chatId),
            null,
            null,
            "seq ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val seq = cursor.getInt(1)
                val text = cursor.getString(3)
                val createdAt = cursor.getString(4)
                val workedMillis = cursor.getLong(5)
                val attachmentsJson = cursor.getString(6)
                val workItemsJson = cursor.getString(7)
                messages.add(
                    ChatMessage(
                        id = id,
                        fromUser = cursor.getInt(2) != 0,
                        text = text,
                        createdAt = runCatching { Instant.parse(createdAt) }.getOrDefault(Instant.now()),
                        attachments = ConversationCodec.attachmentsFromJson(attachmentsJson),
                        workedMillis = workedMillis,
                        workItems = ConversationCodec.workItemsFromJson(workItemsJson),
                    ),
                )
                // Reconstruct the policy hash from stored columns to warm the cache.
                hashes[id] = ConversationPolicy.messageHash(
                    seq,
                    messages.last(),
                    attachmentsJson,
                    workItemsJson,
                )
            }
        }
        hashCache[key] = hashes
        return messages
    }

    // ------------------------------------------------------------------ //
    // Deletion
    // ------------------------------------------------------------------ //

    @Synchronized
    fun deleteProject(projectId: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MESSAGES, "project_id = ?", arrayOf(projectId))
            db.delete(TABLE_CHATS, "project_id = ?", arrayOf(projectId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        hashCache.keys.removeAll { it.startsWith("$projectId|") }
    }

    /** True when the store holds no chat row for the project — used by the legacy import. */
    @Synchronized
    fun hasNoChats(projectId: String): Boolean = loadChatsLocked(helper.readableDatabase, projectId).isEmpty()

    private fun cacheKey(projectId: String, chatId: String): String = "$projectId|$chatId"

    companion object {
        private const val TAG = "ConversationStore"
        private const val SCHEMA_VERSION = 1
        private const val TABLE_CHATS = "chats"
        private const val TABLE_MESSAGES = "messages"
        private const val WHERE_PROJECT_CHAT = "project_id = ? AND chat_id = ?"
        private const val WHERE_PROJECT_CHAT_MESSAGE = "project_id = ? AND chat_id = ? AND message_id = ?"

        @Volatile
        private var instance: ConversationStore? = null

        /** Process-wide single connection; creates the database lazily on first use. */
        fun get(appContext: Context): ConversationStore =
            instance ?: synchronized(this) {
                instance ?: ConversationStore(
                    appContext.applicationContext,
                    File(appContext.applicationContext.filesDir, "conversations.db"),
                ).also { instance = it }
            }

        /** For tests/maintenance tooling only — releases the singleton reference. */
        fun resetForTests() {
            synchronized(this) {
                instance?.let { store ->
                    runCatching { store.helper.close() }
                        .onFailure { AppLog.w(TAG, "Failed to close conversation store", it) }
                }
                instance = null
            }
        }
    }
}
