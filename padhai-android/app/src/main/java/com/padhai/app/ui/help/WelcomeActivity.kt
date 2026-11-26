package com.padhai.app.ui.help

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.padhai.app.databinding.ActivityWelcomeBinding
import com.padhai.app.utils.UserScope
import kotlin.math.abs

/**
 * A short welcome tour (3 slides). It opens once for every new user,
 * and can be opened again from Profile or from Help & Tips.
 */
class WelcomeActivity : AppCompatActivity() {

    private class Page(val emoji: String, val title: String, val body: String)

    private val pages = listOf(
        Page(
            "\uD83D\uDCDA",
            "Add your notes",
            "Paste your notes, or tap the + button to attach a PDF or a photo of your notes. " +
                "PadhAI reads them and writes a short summary for you."
        ),
        Page(
            "\uD83D\uDCAC",
            "Ask, then practice",
            "Ask questions about your notes in the chat. Under the summary, tap Mind Map, " +
                "Quiz or Flashcards to practice with the same notes."
        ),
        Page(
            "\u23F1\uFE0F",
            "Stay on track",
            "Save a chat to find it later in History. Use the Focus timer to study in short " +
                "sessions, and watch your streak and XP grow in Analytics."
        )
    )

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var gestureDetector: GestureDetector
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        // Once it has been shown, do not show it again by itself.
        markSeen(this)

        binding.btnSkip.setOnClickListener { finish() }
        binding.btnBackPage.setOnClickListener {
            if (page > 0) showPage(page - 1)
        }
        binding.btnNext.setOnClickListener {
            if (page < pages.size - 1) showPage(page + 1) else finish()
        }

        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (abs(velocityX) > abs(velocityY) && abs(velocityX) > 800f) {
                        if (velocityX < 0 && page < pages.size - 1) showPage(page + 1)
                        if (velocityX > 0 && page > 0) showPage(page - 1)
                        return true
                    }
                    return false
                }
            }
        )

        showPage(0)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun showPage(index: Int) {
        page = index
        val current = pages[index]

        binding.tvEmoji.text = current.emoji
        binding.tvTitle.text = current.title
        binding.tvBody.text = current.body

        val dots = StringBuilder()
        for (i in pages.indices) {
            dots.append(if (i == index) "\u25CF" else "\u25CB")
            if (i < pages.size - 1) dots.append("  ")
        }
        binding.tvDots.text = dots.toString()

        val isLast = index == pages.size - 1
        binding.btnNext.text = if (isLast) "Get started" else "Next"
        binding.btnSkip.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
        binding.btnBackPage.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE

        binding.pageContent.alpha = 0f
        binding.pageContent.animate().alpha(1f).setDuration(220L).start()
    }

    companion object {
        private const val PREFS_NAME = "onboarding"
        private const val KEY_SEEN = "welcome_seen"

        fun isSeen(context: Context): Boolean =
            UserScope.prefs(context, PREFS_NAME).getBoolean(KEY_SEEN, false)

        private fun markSeen(context: Context) {
            UserScope.prefs(context, PREFS_NAME).edit().putBoolean(KEY_SEEN, true).apply()
        }

        fun intent(context: Context): Intent = Intent(context, WelcomeActivity::class.java)
    }
}
