package com.padhai.app.ui.learn

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Asks the user which notes to use when no chat is open:
 * pick one of the saved chats, or go to Learn and start a new one.
 */
object NotesChooser {

    fun show(
        context: Context,
        onPickPrevious: () -> Unit,
        onNewChat: () -> Unit
    ) {
        val hasSaved = ChatSessionStore.list(context).isNotEmpty()

        val message = if (hasSaved) {
            "No chat is open right now. Pick one of your saved chats, " +
                "or start a new chat and add your notes first."
        } else {
            "You do not have any notes yet. Start a new chat, add your notes " +
                "(paste text, or attach a PDF or photo) and then come back here."
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("Which notes should I use?")
            .setMessage(message)

        if (hasSaved) {
            builder.setPositiveButton("Pick a saved chat") { _, _ -> onPickPrevious() }
            builder.setNeutralButton("Start a new chat") { _, _ -> onNewChat() }
            builder.setNegativeButton("Cancel", null)
        } else {
            builder.setPositiveButton("Start a new chat") { _, _ -> onNewChat() }
            builder.setNegativeButton("Cancel", null)
        }

        builder.show()
    }
}
