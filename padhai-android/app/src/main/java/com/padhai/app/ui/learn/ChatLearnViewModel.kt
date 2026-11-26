package com.padhai.app.ui.learn

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.util.UUID

/**
 * Keeps the part of the chat that the AI question/answer list does not know about:
 * what the user sent as notes, the summary, and error messages.
 * Question/answer messages themselves stay in LearnViewModel.chatMessages.
 * Also remembers which saved chat (if any) this chat belongs to.
 */
class ChatLearnViewModel : ViewModel() {

    private val _base = MutableLiveData<List<ChatItem>>(emptyList())
    val base: LiveData<List<ChatItem>> = _base

    /** True between "start summarising" and the summary (or error) arriving. */
    var awaitingSummary: Boolean = false

    /** The notes text the user pasted (null when a file was sent). */
    var pendingNotes: String? = null

    /** For chats that started from a file, for example "PDF  notes.pdf". Empty for pasted notes. */
    var sourceLabel: String = ""

    /** Id of the saved chat this one is stored as (null if it was never saved). */
    var sessionId: String? = null

    /** A fingerprint of the chat when it was last saved or opened, to know if it changed since. */
    var savedSignature: Int = 0

    var summaryText: String = ""
        private set

    var restoreChecked: Boolean = false

    val hasSummary: Boolean
        get() = summaryText.isNotBlank()

    fun isEmpty(): Boolean = _base.value.isNullOrEmpty()

    private fun add(item: ChatItem) {
        val list = _base.value.orEmpty().toMutableList()
        list.add(item)
        _base.value = list
    }

    private fun newId(): String = UUID.randomUUID().toString()

    fun addUserText(text: String) = add(ChatItem.UserText(newId(), text))

    fun addUserFile(label: String) = add(ChatItem.UserFile(newId(), label))

    fun addAiText(text: String) = add(ChatItem.AiText(newId(), text))

    fun addError(text: String) = add(ChatItem.Error(newId(), text))

    fun addSummary(text: String) {
        summaryText = text
        add(ChatItem.Summary(newId(), text))
    }

    /** Rebuilds the chat from a saved summary only (used when nothing else is known). */
    fun restore(summary: String) {
        summaryText = summary
        _base.value = listOf(ChatItem.Summary(newId(), summary))
    }

    /** Rebuilds the top part of the chat from a saved session. */
    fun loadFrom(session: ChatSession) {
        awaitingSummary = false
        pendingNotes = null
        sessionId = session.id
        sourceLabel = session.sourceLabel
        summaryText = session.summary

        val items = ArrayList<ChatItem>()
        if (session.sourceLabel.isNotBlank()) {
            items.add(ChatItem.UserFile(newId(), session.sourceLabel))
        } else if (session.notes.isNotBlank()) {
            items.add(ChatItem.UserText(newId(), session.notes))
        }
        items.add(ChatItem.Summary(newId(), session.summary))
        _base.value = items
    }

    fun reset() {
        awaitingSummary = false
        pendingNotes = null
        sourceLabel = ""
        sessionId = null
        savedSignature = 0
        summaryText = ""
        _base.value = emptyList()
    }
}
