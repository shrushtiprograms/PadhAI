package com.padhai.app.ui.learn

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.padhai.app.R

/**
 * Everything that can appear as a row in the chat.
 * (Not a "sealed" class on purpose: the older build tools in this project cannot handle them.)
 */
abstract class ChatItem {
    abstract val id: String

    object Welcome : ChatItem() {
        override val id: String = "welcome"
    }

    data class UserText(override val id: String, val text: String) : ChatItem()
    data class UserFile(override val id: String, val label: String) : ChatItem()
    data class AiText(override val id: String, val text: String) : ChatItem()
    data class Summary(override val id: String, val text: String) : ChatItem()
    data class Error(override val id: String, val text: String) : ChatItem()

    /** Shown while the notes are being summarised (contains the 2048 game). */
    object Loading : ChatItem() {
        override val id: String = "loading"
    }

    /** Small "Thinking..." bubble while a chat answer is on its way. */
    object Typing : ChatItem() {
        override val id: String = "typing"
    }
}

/** Turns simple markdown (headings, bullets, **bold**) from the AI into styled text. */
object ChatText {
    fun render(text: String): CharSequence {
        val normalized = text
            .replace("\r\n", "\n")
            .replace("```", "")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "  \u2022 ")
            .replace(Regex("(?m)^\\s*(\\d+)\\.\\s+"), "  $1. ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        val builder = SpannableStringBuilder(normalized)

        // Headings (#, ## ...)
        Regex("(?m)^(#{1,6})\\s*(.+)$").findAll(normalized).toList().asReversed().forEach { match ->
            val level = match.groupValues[1].length
            val fullStart = match.range.first
            val fullEnd = match.range.last + 1
            val titleStart = fullStart + level + 1
            if (titleStart <= fullEnd) {
                builder.replace(fullStart, titleStart, "")
                val newEnd = fullEnd - (titleStart - fullStart)
                builder.setSpan(StyleSpan(Typeface.BOLD), fullStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val size = when (level) {
                    1 -> 1.18f
                    2 -> 1.12f
                    else -> 1.06f
                }
                builder.setSpan(RelativeSizeSpan(size), fullStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // Bold text **...**
        Regex("\\*\\*(.+?)\\*\\*").findAll(builder.toString()).toList().asReversed().forEach { match ->
            val inner = match.groupValues[1]
            val start = match.range.first
            val end = match.range.last + 1
            builder.replace(start, end, inner)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, start + inner.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Inline code `...`
        Regex("`(.+?)`").findAll(builder.toString()).toList().asReversed().forEach { match ->
            val inner = match.groupValues[1]
            val start = match.range.first
            val end = match.range.last + 1
            builder.replace(start, end, inner)
        }

        return builder
    }
}

class ChatLearnAdapter(
    private val onSampleNotes: () -> Unit,
    private val onMindMap: () -> Unit,
    private val onQuiz: () -> Unit,
    private val onFlashcards: () -> Unit,
    private val onReadAloud: (String) -> Unit
) : ListAdapter<ChatItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private class UserHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
    }

    private class AiHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
    }

    private class WelcomeHolder(view: View) : RecyclerView.ViewHolder(view) {
        val btnSample: View = view.findViewById(R.id.btnSampleNotes)
    }

    private class SummaryHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSummary: TextView = view.findViewById(R.id.tvSummary)
        val chipMindMap: Chip = view.findViewById(R.id.chipMindMap)
        val chipQuiz: Chip = view.findViewById(R.id.chipQuiz)
        val chipFlashcards: Chip = view.findViewById(R.id.chipFlashcards)
        val chipReadAloud: Chip = view.findViewById(R.id.chipReadAloud)
    }

    private class LoadingHolder(view: View) : RecyclerView.ViewHolder(view) {
        val game: Game2048View = view.findViewById(R.id.game2048)
        val tvScore: TextView = view.findViewById(R.id.tvGameScore)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ChatItem.Welcome -> TYPE_WELCOME
        is ChatItem.UserText -> TYPE_USER
        is ChatItem.UserFile -> TYPE_USER
        is ChatItem.Summary -> TYPE_SUMMARY
        is ChatItem.Loading -> TYPE_LOADING
        is ChatItem.AiText -> TYPE_AI
        is ChatItem.Error -> TYPE_AI
        is ChatItem.Typing -> TYPE_AI
        else -> TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_WELCOME -> WelcomeHolder(inflater.inflate(R.layout.item_chat_welcome, parent, false))
            TYPE_USER -> UserHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_SUMMARY -> SummaryHolder(inflater.inflate(R.layout.item_chat_summary, parent, false))
            TYPE_LOADING -> LoadingHolder(inflater.inflate(R.layout.item_chat_loading, parent, false))
            else -> AiHolder(inflater.inflate(R.layout.item_chat_ai, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatItem.UserText -> (holder as UserHolder).tvMessage.text = item.text
            is ChatItem.UserFile -> (holder as UserHolder).tvMessage.text = item.label
            is ChatItem.AiText -> bindAi(holder as AiHolder, ChatText.render(item.text), false)
            is ChatItem.Error -> bindAi(holder as AiHolder, item.text, true)
            is ChatItem.Typing -> bindAi(holder as AiHolder, "Thinking...", false)
            is ChatItem.Summary -> bindSummary(holder as SummaryHolder, item)
            is ChatItem.Welcome -> (holder as WelcomeHolder).btnSample.setOnClickListener { onSampleNotes() }
            is ChatItem.Loading -> Unit
        }
    }

    private fun bindAi(holder: AiHolder, text: CharSequence, isError: Boolean) {
        holder.tvMessage.text = text
        val colorRes = if (isError) R.color.error else R.color.text_primary
        holder.tvMessage.setTextColor(ContextCompat.getColor(holder.itemView.context, colorRes))
    }

    private fun bindSummary(holder: SummaryHolder, item: ChatItem.Summary) {
        holder.tvSummary.text = ChatText.render(item.text)
        holder.chipMindMap.setOnClickListener { onMindMap() }
        holder.chipQuiz.setOnClickListener { onQuiz() }
        holder.chipFlashcards.setOnClickListener { onFlashcards() }
        holder.chipReadAloud.setOnClickListener { onReadAloud(item.text) }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is LoadingHolder) {
            holder.game.onScoreChanged = { score, best ->
                holder.tvScore.text = "Swipe to merge  |  Score: $score  |  Best: $best"
            }
            holder.game.start()
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is LoadingHolder) {
            holder.game.stop()
        }
        super.onViewDetachedFromWindow(holder)
    }

    private class DiffCallback : DiffUtil.ItemCallback<ChatItem>() {
        override fun areItemsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_WELCOME = 0
        private const val TYPE_USER = 1
        private const val TYPE_AI = 2
        private const val TYPE_SUMMARY = 3
        private const val TYPE_LOADING = 4
    }
}
