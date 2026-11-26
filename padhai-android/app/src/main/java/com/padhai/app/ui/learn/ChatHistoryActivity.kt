package com.padhai.app.ui.learn

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.padhai.app.R
import com.padhai.app.databinding.ActivityChatHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full screen list of saved chats.
 * - From Learn: tap a chat to open it.
 * - From Quiz / Flashcards / Mind Map ("pick" mode): tap a chat to use its notes.
 * Either way the chosen chat id goes back to the screen that opened this one.
 */
class ChatHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatHistoryBinding
    private lateinit var adapter: HistoryAdapter

    private var allSessions: List<ChatSession> = emptyList()
    private var pickMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        pickMode = intent.getStringExtra(EXTRA_MODE) == MODE_PICK

        binding.tvScreenTitle.text = if (pickMode) "Choose a chat" else "History"
        binding.tvScreenSubtitle.text = if (pickMode) {
            "Tap a chat to use its notes."
        } else {
            "Tap a chat to open it again."
        }
        binding.btnBack.setOnClickListener { finish() }

        adapter = HistoryAdapter(
            onOpen = { session -> returnSession(session) },
            onDelete = { session -> confirmDelete(session) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })

        loadSessions()
    }

    private fun loadSessions() {
        allSessions = ChatSessionStore.list(this)
        applyFilter()
    }

    private fun applyFilter() {
        val query = binding.etSearch.text.toString().trim().lowercase(Locale.getDefault())
        val shown = if (query.isEmpty()) {
            allSessions
        } else {
            allSessions.filter {
                it.title.lowercase(Locale.getDefault()).contains(query) ||
                    it.summary.lowercase(Locale.getDefault()).contains(query) ||
                    it.notes.lowercase(Locale.getDefault()).contains(query)
            }
        }
        adapter.submitList(shown)

        val isEmpty = shown.isEmpty()
        binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.tvEmpty.text = when {
            allSessions.isEmpty() ->
                "No saved chats yet.\n\nStart a chat in Learn. When you tap New, choose Save and it will appear here."
            else -> "No chats match your search."
        }
        binding.etSearch.visibility = if (allSessions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun returnSession(session: ChatSession) {
        val data = Intent().putExtra(EXTRA_SESSION_ID, session.id)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun confirmDelete(session: ChatSession) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this chat?")
            .setMessage("\"${session.title}\" will be removed from your history.")
            .setPositiveButton("Delete") { _, _ ->
                ChatSessionStore.delete(this, session.id)
                loadSessions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- adapter

    private class HistoryAdapter(
        private val onOpen: (ChatSession) -> Unit,
        private val onDelete: (ChatSession) -> Unit
    ) : ListAdapter<ChatSession, HistoryAdapter.Holder>(Diff()) {

        private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvHistoryTitle)
            val tvMeta: TextView = view.findViewById(R.id.tvHistoryMeta)
            val tvPreview: TextView = view.findViewById(R.id.tvHistoryPreview)
            val btnDelete: MaterialButton = view.findViewById(R.id.btnHistoryDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_chat, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val session = getItem(position)
            val questions = session.messages.count { it.role == "user" }
            val language = if (session.language == "hi") "Hindi" else "English"
            val questionsText = if (questions == 1) "1 question" else "$questions questions"

            holder.tvTitle.text = session.title.ifBlank { "Untitled chat" }
            holder.tvMeta.text =
                "${dateFormat.format(Date(session.updatedAt))}  |  $questionsText  |  $language"
            holder.tvPreview.text = session.summary.replace("\n", " ").take(160)
            holder.itemView.setOnClickListener { onOpen(session) }
            holder.btnDelete.setOnClickListener { onDelete(session) }
        }

        private class Diff : DiffUtil.ItemCallback<ChatSession>() {
            override fun areItemsTheSame(oldItem: ChatSession, newItem: ChatSession): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatSession, newItem: ChatSession): Boolean =
                oldItem == newItem
        }
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_PICK = "pick"
        const val EXTRA_SESSION_ID = "extra_session_id"

        /** Intent that opens the list in "choose a chat" mode (used by Quiz, Flashcards, Mind Map). */
        fun pickIntent(context: Context): Intent =
            Intent(context, ChatHistoryActivity::class.java).putExtra(EXTRA_MODE, MODE_PICK)

        /** Intent that opens the normal History list. */
        fun openIntent(context: Context): Intent =
            Intent(context, ChatHistoryActivity::class.java)
    }
}
