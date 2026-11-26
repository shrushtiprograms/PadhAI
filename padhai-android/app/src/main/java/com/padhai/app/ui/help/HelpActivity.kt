package com.padhai.app.ui.help

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.padhai.app.R
import com.padhai.app.databinding.ActivityHelpBinding

/**
 * Help & Tips: short explanations of every part of the app, so users can
 * learn again at any time. Tap a section to open or close it.
 */
class HelpActivity : AppCompatActivity() {

    private class Section(val title: String, val body: String)

    private val sections = listOf(
        Section(
            "Getting started",
            "1. Open the Learn tab.\n" +
                "2. Paste your notes in the box at the bottom, or tap + to attach a PDF or photo.\n" +
                "3. PadhAI writes a short summary.\n" +
                "4. Ask questions, or tap Mind Map, Quiz or Flashcards under the summary."
        ),
        Section(
            "Adding notes",
            "- Paste a few lines of text and tap Send.\n" +
                "- Tap + and choose PDF document (up to 10 MB), Photo from gallery, or Take a photo. " +
                "Printed and handwritten notes both work.\n" +
                "- The English / Hindi buttons at the top decide the language of the summary and answers.\n" +
                "- No notes handy? Tap Try with sample notes."
        ),
        Section(
            "Asking questions",
            "After the summary appears, everything you type is a question about your notes. " +
                "Tap Read aloud under the summary to listen to it. " +
                "Tap New when you want to start again with different notes."
        ),
        Section(
            "Saving chats and History",
            "Tap New and choose Save to keep a chat. Tap History (top right of the Learn tab) " +
                "to see your saved chats. You can search them, open one to continue, or delete it. " +
                "Chats are stored on this phone, separately for every account."
        ),
        Section(
            "Quiz and Flashcards",
            "Open the Quiz tab. Start Quiz makes questions from your notes. " +
                "Study Flashcards makes cards you can flip. " +
                "If no chat is open, you can pick one of your saved chats. " +
                "Your scores appear under Recent History, and you can leave a quiz half way and resume it later."
        ),
        Section(
            "Mind Map",
            "A mind map shows your notes as topics and points. Drag to move, pinch to zoom, " +
                "tap a branch to open or close it, and double-tap to fit the whole map on the screen. " +
                "Open it from the buttons under a summary, or from the Quiz tab."
        ),
        Section(
            "Focus timer",
            "Focus uses the Pomodoro method: study for 25 minutes, then take a short break. " +
                "Tap Start, and you get an alarm when the time is up. Finished sessions give you XP. " +
                "Customize Timer changes the minutes. " +
                "Activate Focus Mode switches your phone to Do Not Disturb so notifications stay quiet. " +
                "Android asks for permission the first time."
        ),
        Section(
            "Analytics, XP and streaks",
            "Analytics is your study report. Day Streak counts the days in a row you studied. " +
                "XP and Level grow when you finish focus sessions and daily quests. " +
                "You can also see your study time, quiz scores and the flashcards you viewed today."
        ),
        Section(
            "If something goes wrong",
            "- Summary or answer fails: check your internet, wait a minute and try again. " +
                "The AI service can be busy.\n" +
                "- PDF too big: use a PDF under 10 MB, or attach a photo of the page.\n" +
                "- Camera does not open: allow the camera permission in your phone Settings.\n" +
                "- Lost a chat: open History.\n" +
                "- Seeing another person's data: log out from Profile and log in again.\n" +
                "- Want the introduction again: tap Show welcome tour below."
        )
    )

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnReplayTour.setOnClickListener {
            startActivity(WelcomeActivity.intent(this))
        }

        val inflater = LayoutInflater.from(this)
        sections.forEachIndexed { index, section ->
            val row = inflater.inflate(R.layout.item_help_section, binding.layoutSections, false)
            val tvTitle: TextView = row.findViewById(R.id.tvHelpTitle)
            val tvToggle: TextView = row.findViewById(R.id.tvHelpToggle)
            val tvBody: TextView = row.findViewById(R.id.tvHelpBody)
            val header: View = row.findViewById(R.id.layoutHelpHeader)

            tvTitle.text = section.title
            tvBody.text = section.body

            // The first section starts open, the others closed.
            var open = index == 0
            fun apply() {
                tvBody.visibility = if (open) View.VISIBLE else View.GONE
                tvToggle.text = if (open) "-" else "+"
            }
            apply()

            header.setOnClickListener {
                open = !open
                apply()
            }
            binding.layoutSections.addView(row)
        }
    }
}
