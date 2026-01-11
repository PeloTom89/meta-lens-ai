package com.metalens.app.history

import android.content.Context
import android.content.SharedPreferences
import com.metalens.app.conversation.ChatMessage
import com.metalens.app.conversation.ChatRole
import com.metalens.app.conversation.ConversationRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class ConversationHistoryStorage(
    context: Context,
) {
    private val appContext = context.applicationContext

    /**
     * Legacy storage (migration-only).
     */
    private val legacyPrefs: SharedPreferences =
        appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private val historyDir: File by lazy {
        File(appContext.filesDir, HISTORY_DIR).also { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    init {
        migrateFromLegacyPrefsIfNeeded()
    }

    fun saveFromRuntime(): Boolean {
        val uiState = ConversationRuntime.uiState.value
        val id = uiState.activeConversationId ?: return false
        val startedAtMs = uiState.conversationStartedAtMs ?: return false
        val startIndex = uiState.conversationStartMessageIndex ?: 0

        val messages =
            uiState.messages
                .drop(startIndex.coerceIn(0, uiState.messages.size))
                .filter { it.text.isNotBlank() }
                // Avoid persisting "..." placeholders that are used while STT is pending.
                .filterNot { it.text.trim() == "…" }
                .map { it.toHistoryMessage() }

        if (messages.isEmpty()) {
            // Nothing meaningful to persist, but clear the meta so we don't keep trying.
            ConversationRuntime.clearActiveConversationMeta()
            return false
        }

        val record =
            ConversationHistoryRecord(
                id = id,
                startedAtMs = startedAtMs,
                messages = messages,
            )

        val ok = saveConversation(record)
        if (ok) {
            ConversationRuntime.clearActiveConversationMeta()
        }
        return ok
    }

    fun saveConversation(record: ConversationHistoryRecord): Boolean {
        return try {
            // If this id already exists, overwrite the existing file. Otherwise create a new one.
            val file = findFileById(record.id) ?: fileFor(record.startedAtMs, record.id)
            writeAtomically(file, record.toJson().toString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun getAllConversations(): List<ConversationHistoryRecord> {
        return try {
            val files = historyDir.listFiles()?.filter { it.isFile && it.extension == "json" }.orEmpty()
            files.mapNotNull { f ->
                runCatching { JSONObject(f.readText()) }.getOrNull()?.toRecord()
            }.sortedByDescending { it.startedAtMs }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun getConversation(id: String): ConversationHistoryRecord? {
        return try {
            val file = findFileById(id) ?: return null
            JSONObject(file.readText()).toRecord()
        } catch (_: Throwable) {
            null
        }
    }

    fun deleteConversation(id: String): Boolean {
        return try {
            findFileById(id)?.delete() ?: false
        } catch (_: Throwable) {
            false
        }
    }

    fun deleteAll(): Boolean {
        return try {
            historyDir.listFiles()?.forEach { it.delete() }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun ConversationHistoryRecord.toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("startedAtMs", startedAtMs)
        val messagesArr = JSONArray()
        for (m in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", m.role.name)
            msgObj.put("text", m.text)
            messagesArr.put(msgObj)
        }
        obj.put("messages", messagesArr)
        return obj
    }

    private fun JSONObject.toRecord(): ConversationHistoryRecord? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val startedAtMs = optLong("startedAtMs", -1L)
        if (startedAtMs <= 0L) return null

        val messagesJson = optJSONArray("messages") ?: JSONArray()
        val messages = ArrayList<ConversationHistoryMessage>(messagesJson.length())
        for (i in 0 until messagesJson.length()) {
            val msgObj = messagesJson.optJSONObject(i) ?: continue
            val roleStr = msgObj.optString("role").takeIf { it.isNotBlank() } ?: continue
            val text = msgObj.optString("text", "")
            val role = runCatching { ConversationHistoryRole.valueOf(roleStr) }.getOrNull() ?: continue
            messages.add(ConversationHistoryMessage(role = role, text = text))
        }

        return ConversationHistoryRecord(
            id = id,
            startedAtMs = startedAtMs,
            messages = messages,
        )
    }

    private fun migrateFromLegacyPrefsIfNeeded() {
        val raw = legacyPrefs.getString(LEGACY_KEY_CONVERSATIONS, null) ?: return
        val migratedOnce = legacyPrefs.getBoolean(LEGACY_KEY_MIGRATED, false)
        if (migratedOnce) return

        val records =
            runCatching { decodeLegacy(JSONArray(raw)) }
                .getOrElse { emptyList() }

        // Best-effort migration: write each conversation to its own file.
        for (record in records.take(MAX_RECORDS)) {
            runCatching {
                val file = fileFor(record.startedAtMs, record.id)
                if (!file.exists()) {
                    writeAtomically(file, record.toJson().toString())
                }
            }
        }

        // Mark migrated and remove legacy payload to reclaim space.
        legacyPrefs.edit()
            .putBoolean(LEGACY_KEY_MIGRATED, true)
            .remove(LEGACY_KEY_CONVERSATIONS)
            .apply()
    }

    private fun decodeLegacy(arr: JSONArray): List<ConversationHistoryRecord> {
        val out = ArrayList<ConversationHistoryRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(obj.toRecord() ?: continue)
        }
        return out
    }

    private fun fileFor(startedAtMs: Long, id: String): File {
        // startedAtMs prefix makes listing/sorting cheaper and stable.
        return File(historyDir, "${startedAtMs}_${id}.json")
    }

    private fun findFileById(id: String): File? {
        return historyDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith("_${id}.json") }
    }

    private fun writeAtomically(target: File, contents: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(contents.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // Fallback: try replacing existing file.
            target.delete()
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("Failed to write ${target.absolutePath}")
            }
        }
    }

    private fun ChatMessage.toHistoryMessage(): ConversationHistoryMessage {
        val role =
            when (role) {
                ChatRole.User -> ConversationHistoryRole.User
                ChatRole.Ai -> ConversationHistoryRole.Ai
            }
        return ConversationHistoryMessage(role = role, text = text)
    }

    private companion object {
        private const val HISTORY_DIR = "conversation_history"

        // Legacy (SharedPreferences) migration-only constants
        private const val LEGACY_PREFS_NAME = "metalens_conversations"
        private const val LEGACY_KEY_CONVERSATIONS = "saved_conversations"
        private const val LEGACY_KEY_MIGRATED = "history_migrated_to_files_v1"

        private const val MAX_RECORDS = 100
    }
}

