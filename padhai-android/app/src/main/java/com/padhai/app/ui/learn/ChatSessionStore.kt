package com.padhai.app.ui.learn

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.padhai.app.data.ChatHistoryEntry
import com.padhai.app.data.StudyDataManager
import com.padhai.app.utils.UserScope
import java.util.UUID

/** One saved chat: the notes, the summary and the questions and answers. */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val language: String = "en",
    val notes: String = "",
    val summary: String = "",
    /** Set when the chat started from a file (for example "PDF  notes.pdf"). Empty for pasted notes. */
    val sourceLabel: String = "",
    val messages: List<ChatHistoryEntry> = emptyList()
)

/**
 * Saves chats in the phone, separately for every logged in user.
 * Also remembers which chat is "open" right now, so that Learn, Quiz and Mind Map
 * all work on the same notes.
 */
object ChatSessionStore {

    private const val PREFS_NAME = "chat_sessions"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_CURRENT_ID = "current_session_id"
    private const val KEY_CURRENT_TITLE = "current_title"
    private const val MAX_SESSIONS = 40

    private val gson = Gson()

    private fun prefs(context: Context) = UserScope.prefs(context, PREFS_NAME)

    /** All saved chats, newest first. */
    fun list(context: Context): List<ChatSession> {
        val json = prefs(context).getString(KEY_SESSIONS, "[]") ?: "[]"
        return try {
            val type = object : TypeToken<List<ChatSession>>() {}.type
            val parsed: List<ChatSession> = gson.fromJson(json, type) ?: emptyList()
            parsed.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: String): ChatSession? = list(context).firstOrNull { it.id == id }

    /** Adds the chat, or replaces it if it was saved before. */
    fun save(context: Context, session: ChatSession) {
        val all = list(context).filter { it.id != session.id }.toMutableList()
        all.add(0, session)
        val trimmed = all.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS)
        prefs(context).edit().putString(KEY_SESSIONS, gson.toJson(trimmed)).apply()
    }

    fun delete(context: Context, id: String) {
        val remaining = list(context).filter { it.id != id }
        prefs(context).edit().putString(KEY_SESSIONS, gson.toJson(remaining)).apply()
        if (currentSessionId(context) == id) {
            setCurrentSessionId(context, null)
        }
    }

    // ---------------------------------------------------------- "open" chat

    fun currentSessionId(context: Context): String? =
        prefs(context).getString(KEY_CURRENT_ID, null)

    fun setCurrentSessionId(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id == null) remove(KEY_CURRENT_ID) else putString(KEY_CURRENT_ID, id)
        }.apply()
    }

    fun currentTitle(context: Context): String =
        prefs(context).getString(KEY_CURRENT_TITLE, "") ?: ""

    fun setCurrentTitle(context: Context, title: String) {
        prefs(context).edit().putString(KEY_CURRENT_TITLE, title).apply()
    }

    fun clearCurrent(context: Context) {
        prefs(context).edit().remove(KEY_CURRENT_ID).remove(KEY_CURRENT_TITLE).apply()
    }

    /**
     * Makes a saved chat the one that Quiz, Flashcards and Mind Map use.
     * Returns false if the chat no longer exists.
     */
    fun activate(context: Context, id: String): Boolean {
        val session = get(context, id) ?: return false
        StudyDataManager.setCurrentNotes(context, session.notes, session.summary)
        setCurrentSessionId(context, session.id)
        setCurrentTitle(context, session.title)
        return true
    }

    // -------------------------------------------------------------- titles

    /** A short readable name for a chat. */
    fun buildTitle(notes: String, sourceLabel: String): String {
        if (sourceLabel.isNotBlank()) {
            return sourceLabel.trim()
        }
        val firstLine = notes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        if (firstLine.isEmpty()) return "Untitled chat"
        return if (firstLine.length <= 40) firstLine else firstLine.take(40).trimEnd() + "..."
    }
}
