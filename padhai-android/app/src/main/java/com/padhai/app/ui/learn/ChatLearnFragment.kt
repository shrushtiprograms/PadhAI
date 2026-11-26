package com.padhai.app.ui.learn

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.padhai.app.R
import com.padhai.app.data.ChatHistoryEntry
import com.padhai.app.data.StudyDataManager
import com.padhai.app.data.model.ChatMessage
import com.padhai.app.databinding.FragmentChatLearnBinding
import com.padhai.app.ui.camera.CameraActivity
import com.padhai.app.utils.TTSManager
import java.util.UUID

/**
 * Chat-style Learn screen.
 * - Paste notes, or attach a PDF / photo with the + button.
 * - The first reply is a summary, with buttons for Mind Map, Quiz, Flashcards and Read aloud.
 * - After the summary, every message you send is a question about your notes.
 * - "New" asks whether to save the chat to History. "History" opens saved chats.
 */
class ChatLearnFragment : Fragment() {

    private enum class AttachKind { PDF, IMAGE }

    private var _binding: FragmentChatLearnBinding? = null
    private val binding get() = _binding!!

    private lateinit var learnVm: LearnViewModel
    private lateinit var chatVm: ChatLearnViewModel
    private lateinit var adapter: ChatLearnAdapter
    private var ttsManager: TTSManager? = null

    private var attachedUri: Uri? = null
    private var attachedKind: AttachKind? = null
    private var attachedName: String = ""

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onFileChosen(it, AttachKind.IMAGE) }
    }

    private val pickPdfLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onFileChosen(it, AttachKind.PDF) }
    }

    private val takeCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> onFileChosen(uri, AttachKind.IMAGE) }
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openCamera()
        } else {
            Toast.makeText(context, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val openHistoryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getStringExtra(ChatHistoryActivity.EXTRA_SESSION_ID)
            if (id != null && _binding != null) {
                handleUnsavedThen { loadSession(id) }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        learnVm = ViewModelProvider(this)[LearnViewModel::class.java]
        chatVm = ViewModelProvider(this)[ChatLearnViewModel::class.java]
        _binding = FragmentChatLearnBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ttsManager = TTSManager(requireContext()) { }

        setupList()
        setupComposer()
        setupTopBar()
        restoreIfNeeded()
        observeViewModels()
        render()
    }

    override fun onResume() {
        super.onResume()
        syncWithOpenChat()
    }

    override fun onDestroyView() {
        ttsManager?.stop()
        ttsManager?.shutdown()
        ttsManager = null
        _binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ setup

    private fun setupList() {
        adapter = ChatLearnAdapter(
            onSampleNotes = {
                if (chatVm.hasSummary) {
                    Toast.makeText(context, "Tap New to start a fresh chat first", Toast.LENGTH_SHORT).show()
                } else {
                    startFromText(SAMPLE_NOTES)
                }
            },
            onMindMap = { openMindMap() },
            onQuiz = { openQuizTab() },
            onFlashcards = { openQuizTab() },
            onReadAloud = { text -> toggleReadAloud(text) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = adapter
        binding.rvMessages.itemAnimator = null
    }

    private fun setupComposer() {
        binding.btnAttach.setOnClickListener { showAttachMenu(it) }
        binding.btnSend.setOnClickListener { onSendClicked() }
        binding.btnRemoveAttachment.setOnClickListener { clearAttachment() }
    }

    private fun setupTopBar() {
        binding.btnNewChat.setOnClickListener { confirmNewChat() }
        binding.btnHistory.setOnClickListener {
            openHistoryLauncher.launch(ChatHistoryActivity.openIntent(requireContext()))
        }
    }

    /** If the screen was recreated, bring back the open chat so it is not empty. */
    private fun restoreIfNeeded() {
        if (chatVm.restoreChecked) return
        chatVm.restoreChecked = true
        if (!chatVm.isEmpty()) return

        val ctx = requireContext()
        val savedId = ChatSessionStore.currentSessionId(ctx)
        if (savedId != null && ChatSessionStore.get(ctx, savedId) != null) {
            loadSession(savedId)
            return
        }

        val summary = StudyDataManager.getCurrentSummary(ctx)
        if (summary.isNotBlank()) {
            learnVm.setRestoredSummary(summary)
            chatVm.restore(summary)
        }
    }

    /**
     * If another screen (Quiz, Flashcards, Mind Map) picked a saved chat while this one is empty,
     * show that chat here too, so every screen works on the same notes.
     */
    private fun syncWithOpenChat() {
        if (_binding == null) return
        val ctx = requireContext()
        val currentId = ChatSessionStore.currentSessionId(ctx) ?: return
        if (!chatVm.hasSummary &&
            currentId != chatVm.sessionId &&
            ChatSessionStore.get(ctx, currentId) != null
        ) {
            loadSession(currentId)
        }
    }

    private fun observeViewModels() {
        learnVm.summary.observe(viewLifecycleOwner) { summary ->
            if (chatVm.awaitingSummary && summary.isNotBlank()) {
                chatVm.awaitingSummary = false
                val ctx = requireContext()
                val notes = chatVm.pendingNotes?.takeIf { it.isNotBlank() } ?: summary
                // Quiz, Flashcards and Mind Map read these.
                StudyDataManager.setCurrentNotes(ctx, notes, summary)
                // A brand new chat is not saved yet.
                ChatSessionStore.setCurrentSessionId(ctx, null)
                ChatSessionStore.setCurrentTitle(ctx, ChatSessionStore.buildTitle(notes, chatVm.sourceLabel))
                chatVm.sessionId = null
                chatVm.addSummary(summary)
            }
        }

        learnVm.error.observe(viewLifecycleOwner) { error ->
            if (chatVm.awaitingSummary && !error.isNullOrBlank()) {
                chatVm.awaitingSummary = false
                chatVm.addError(error)
            }
        }

        learnVm.isLoading.observe(viewLifecycleOwner) { render() }
        learnVm.chatLoading.observe(viewLifecycleOwner) { render() }
        learnVm.chatMessages.observe(viewLifecycleOwner) { render() }
        chatVm.base.observe(viewLifecycleOwner) { render() }
    }

    // ----------------------------------------------------------------- render

    private fun qaMessages(): List<ChatMessage> =
        learnVm.chatMessages.value.orEmpty().filter { !it.isConfirmation }

    private fun render() {
        if (_binding == null) return

        val items = ArrayList<ChatItem>()
        items.add(ChatItem.Welcome)
        items.addAll(chatVm.base.value.orEmpty())

        qaMessages().forEach { message ->
            if (message.role == "user") {
                items.add(ChatItem.UserText(message.id, message.content))
            } else {
                items.add(ChatItem.AiText(message.id, message.content))
            }
        }

        val summaryLoading = learnVm.isLoading.value == true
        val answerLoading = learnVm.chatLoading.value == true
        if (summaryLoading) items.add(ChatItem.Loading)
        if (answerLoading) items.add(ChatItem.Typing)

        binding.etMessage.hint = if (chatVm.hasSummary) {
            "Ask a question about your notes"
        } else {
            "Paste your notes here"
        }
        val busy = summaryLoading || answerLoading
        binding.btnSend.isEnabled = !busy
        binding.btnAttach.isEnabled = !busy

        adapter.submitList(items) {
            if (_binding != null && items.size > 1) {
                binding.rvMessages.scrollToPosition(items.size - 1)
            }
        }
    }

    // ------------------------------------------------------------------- send

    private fun languageCode(): String = if (binding.chipHindi.isChecked) "hi" else "en"

    private fun onSendClicked() {
        if (learnVm.isLoading.value == true || learnVm.chatLoading.value == true) return

        val text = binding.etMessage.text.toString().trim()
        val uri = attachedUri
        val kind = attachedKind

        if (uri != null && kind != null) {
            if (chatVm.hasSummary) {
                Toast.makeText(
                    context,
                    "Tap New to start a fresh chat with another file",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            startFromAttachment(uri, kind)
            return
        }

        if (text.isEmpty()) return

        if (chatVm.hasSummary) {
            binding.etMessage.setText("")
            hideKeyboard()
            learnVm.sendChatMessage(text, languageCode())
            return
        }

        if (text.length < MIN_NOTES_CHARS) {
            binding.etMessage.setText("")
            hideKeyboard()
            chatVm.addUserText(text)
            chatVm.addAiText(
                "Please paste your notes (a few lines), or tap + to attach a PDF or photo. " +
                    "I will summarise them for you."
            )
            return
        }

        startFromText(text)
    }

    private fun startFromText(text: String) {
        if (learnVm.isLoading.value == true) return
        binding.etMessage.setText("")
        hideKeyboard()
        chatVm.addUserText(text)
        chatVm.pendingNotes = text
        chatVm.sourceLabel = ""
        chatVm.awaitingSummary = true
        StudyDataManager.setCurrentNotes(requireContext(), text, "")
        learnVm.summarizeNotes(text, "brief", languageCode())
    }

    private fun startFromAttachment(uri: Uri, kind: AttachKind) {
        val label = (if (kind == AttachKind.PDF) "\uD83D\uDCC4 " else "\uD83D\uDDBC ") + attachedName
        hideKeyboard()
        chatVm.addUserFile(label)
        chatVm.pendingNotes = null
        chatVm.sourceLabel = label
        chatVm.awaitingSummary = true
        clearAttachment()

        val language = languageCode()
        when (kind) {
            AttachKind.PDF -> learnVm.summarizePdfFromUri(requireContext(), uri, "brief", language)
            AttachKind.IMAGE -> learnVm.summarizeImageFromUri(requireContext(), uri, "brief", language)
        }
    }

    // ------------------------------------------------------------- attachments

    private fun showAttachMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_PDF, 0, "PDF document")
        popup.menu.add(0, MENU_GALLERY, 1, "Photo from gallery")
        popup.menu.add(0, MENU_CAMERA, 2, "Take a photo")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_PDF -> pickPdfLauncher.launch("application/pdf")
                MENU_GALLERY -> pickImageLauncher.launch("image/*")
                MENU_CAMERA -> checkCameraPermission()
            }
            true
        }
        popup.show()
    }

    private fun onFileChosen(uri: Uri, kind: AttachKind) {
        if (kind == AttachKind.PDF) {
            val size = querySize(uri)
            if (size > MAX_PDF_BYTES) {
                Toast.makeText(context, "This PDF is too large. Please use one under 10 MB.", Toast.LENGTH_LONG).show()
                return
            }
        }
        attachedUri = uri
        attachedKind = kind
        attachedName = queryName(uri) ?: if (kind == AttachKind.PDF) "document.pdf" else "photo"

        val icon = if (kind == AttachKind.PDF) "\uD83D\uDCC4 " else "\uD83D\uDDBC "
        binding.tvAttachment.text = icon + attachedName
        binding.layoutAttachment.visibility = View.VISIBLE
    }

    private fun clearAttachment() {
        attachedUri = null
        attachedKind = null
        attachedName = ""
        if (_binding != null) {
            binding.layoutAttachment.visibility = View.GONE
            binding.tvAttachment.text = ""
        }
    }

    private fun queryName(uri: Uri): String? {
        return try {
            requireContext().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun querySize(uri: Uri): Long {
        return try {
            requireContext().contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun checkCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            openCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        takeCameraLauncher.launch(Intent(requireContext(), CameraActivity::class.java))
    }

    // ----------------------------------------------------------- summary chips

    private fun openMindMap() {
        val notes = StudyDataManager.getCurrentNotes(requireContext()).ifBlank { chatVm.summaryText }
        if (notes.isBlank()) {
            Toast.makeText(context, "Add or summarize some notes first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(requireContext(), MindMapActivity::class.java).apply {
            putExtra(MindMapActivity.EXTRA_NOTES, notes.take(12000))
            putExtra(MindMapActivity.EXTRA_LANG, languageCode())
        }
        startActivity(intent)
    }

    private fun openQuizTab() {
        NavHostFragment.findNavController(this).navigate(R.id.navigation_quiz)
    }

    private fun toggleReadAloud(text: String) {
        val tts = ttsManager ?: return
        if (tts.isSpeaking()) {
            tts.stop()
            return
        }
        tts.setLanguage(languageCode())
        val started = tts.speak(text)
        if (!started) {
            Toast.makeText(context, "Read aloud is not ready yet. Please try again.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------- save / history

    private fun currentSignature(): Int = chatVm.summaryText.hashCode() * 31 + qaMessages().size

    /** True if there is a chat worth saving that is not saved yet (or changed since it was saved). */
    private fun hasUnsavedChanges(): Boolean {
        if (!chatVm.hasSummary) return false
        return chatVm.sessionId == null || currentSignature() != chatVm.savedSignature
    }

    /**
     * Runs [action] after the user decided what to do with the chat that is open now:
     * save it, throw it away, or cancel.
     */
    private fun handleUnsavedThen(action: () -> Unit) {
        if (!hasUnsavedChanges()) {
            action()
            return
        }

        val isChanged = chatVm.sessionId != null
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isChanged) "Save your changes?" else "Save this chat?")
            .setMessage(
                if (isChanged) {
                    "You asked new questions in this chat since you last saved it."
                } else {
                    "Save it to History so you can open it again later."
                }
            )
            .setPositiveButton(if (isChanged) "Save changes" else "Save") { _, _ ->
                if (saveCurrentChat()) action()
            }
            .setNegativeButton(if (isChanged) "Discard changes" else "Discard") { _, _ ->
                action()
            }
            .setNeutralButton("Cancel", null)
        builder.show()
    }

    private fun saveCurrentChat(): Boolean {
        if (!chatVm.hasSummary) return false
        val ctx = requireContext()

        val existing = chatVm.sessionId?.let { ChatSessionStore.get(ctx, it) }
        val notes = StudyDataManager.getCurrentNotes(ctx).ifBlank { chatVm.summaryText }

        val session = ChatSession(
            id = existing?.id ?: UUID.randomUUID().toString(),
            title = existing?.title ?: ChatSessionStore.buildTitle(notes, chatVm.sourceLabel),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            language = languageCode(),
            notes = notes,
            summary = chatVm.summaryText,
            sourceLabel = chatVm.sourceLabel,
            messages = qaMessages().map { ChatHistoryEntry(it.role, it.content, it.timestamp) }
        )
        ChatSessionStore.save(ctx, session)
        ChatSessionStore.setCurrentSessionId(ctx, session.id)
        ChatSessionStore.setCurrentTitle(ctx, session.title)

        chatVm.sessionId = session.id
        chatVm.savedSignature = currentSignature()
        Toast.makeText(context, "Saved to History", Toast.LENGTH_SHORT).show()
        return true
    }

    /** Opens a saved chat in this screen. */
    private fun loadSession(id: String) {
        val ctx = requireContext()
        val session = ChatSessionStore.get(ctx, id)
        if (session == null) {
            Toast.makeText(context, "This chat could not be found", Toast.LENGTH_SHORT).show()
            return
        }

        ttsManager?.stop()
        learnVm.clearCurrentSession()
        chatVm.reset()

        // Quiz, Flashcards and Mind Map use the same notes from now on.
        ChatSessionStore.activate(ctx, session.id)

        learnVm.setRestoredSummary(session.summary)
        session.messages.forEach { entry ->
            learnVm.addChatMessageFromHistory(
                ChatMessage(role = entry.role, content = entry.content, timestamp = entry.timestamp)
            )
        }
        chatVm.loadFrom(session)

        if (session.language == "hi") {
            binding.chipHindi.isChecked = true
        } else {
            binding.chipEnglish.isChecked = true
        }
        clearAttachment()
        binding.etMessage.setText("")

        chatVm.savedSignature = currentSignature()
        render()
    }

    // ---------------------------------------------------------------- new chat

    private fun confirmNewChat() {
        handleUnsavedThen { resetSession() }
    }

    private fun resetSession() {
        if (_binding == null) return
        ttsManager?.stop()
        learnVm.clearCurrentSession()
        chatVm.reset()
        StudyDataManager.setCurrentNotes(requireContext(), "", "")
        ChatSessionStore.clearCurrent(requireContext())
        clearAttachment()
        binding.etMessage.setText("")
        render()
    }

    // ------------------------------------------------------------------- misc

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMessage.windowToken, 0)
    }

    companion object {
        private const val MIN_NOTES_CHARS = 25
        private const val MAX_PDF_BYTES = 10L * 1024L * 1024L
        private const val MENU_PDF = 1
        private const val MENU_GALLERY = 2
        private const val MENU_CAMERA = 3

        private const val SAMPLE_NOTES =
            "Photosynthesis is the process by which green plants make their own food using " +
                "sunlight, water and carbon dioxide. It takes place in the chloroplasts, which " +
                "contain chlorophyll. The process produces glucose and releases oxygen. The " +
                "overall equation is 6CO2 + 6H2O + light energy gives C6H12O6 + 6O2. It has two " +
                "stages: the light reactions in the thylakoid membranes and the Calvin cycle in " +
                "the stroma."
    }
}
