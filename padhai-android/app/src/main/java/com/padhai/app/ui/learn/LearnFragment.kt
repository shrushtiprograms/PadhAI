//LearnFrgment.kt
package com.padhai.app.ui.learn
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.random.Random
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.padhai.app.data.StudyDataManager
import com.padhai.app.databinding.FragmentLearnBinding
import com.padhai.app.ui.camera.CameraActivity
import com.padhai.app.utils.TTSManager
import kotlinx.coroutines.launch
import java.util.Locale
import com.padhai.app.utils.UserScope

class LearnFragment : Fragment() {

    private var _binding: FragmentLearnBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: LearnViewModel
    private lateinit var chatAdapter: ChatAdapter
    private var ttsManager: TTSManager? = null
    private var selectedImageUri: Uri? = null
    private var selectedPdfUri: Uri? = null

    private val loadingHandler = Handler(Looper.getMainLooper())
    private var gameScore = 0

    private val studyTips = listOf(
        "💡 Tip: Try to recall what you already know about this topic.",
        "💡 Tip: Breaking large topics into smaller ideas makes them easier to remember.",
        "💡 Tip: Understanding a concept is better than memorizing it blindly.",
        "💡 Tip: After studying, explain the topic in your own words.",
        "💡 Tip: Look for connections between different concepts.",
        "💡 Tip: Short focused study sessions can improve concentration."
    )

    private data class LoadingChallenge(
        val title: String,
        val subtitle: String,
        val instruction: String,
        val target: String,
        val tip: String
    )

    // The same tap-to-catch game gets a new educational theme while AI works.
    private val loadingChallenges = listOf(
        LoadingChallenge(
            "🔬 Shrink to Microscopic Size",
            "StudyGenie is working · Biology · Hidden worlds",
            "Tap the microscope target to explore!",
            "🔬",
            "Fun fact: A microscope reveals details too small for our eyes."
        ),
        LoadingChallenge(
            "🚀 Escape the Gravity Well",
            "StudyGenie is working · Physics · Space",
            "Catch the rocket before it moves!",
            "🚀",
            "Fun fact: Gravity keeps planets in orbit around the Sun."
        ),
        LoadingChallenge(
            "🧬 Build the DNA Ladder",
            "StudyGenie is working · Biology · Genetics",
            "Tap the DNA target to earn a point!",
            "🧬",
            "Fun fact: DNA stores biological instructions in living things."
        ),
        LoadingChallenge(
            "⚗️ Chemistry Reaction Rescue",
            "StudyGenie is working · Chemistry · Elements",
            "Catch the flask to score a point!",
            "⚗️",
            "Study tip: Track atoms on both sides when balancing equations."
        ),
        LoadingChallenge(
            "🌌 Explore the Memory Galaxy",
            "StudyGenie is working · Learning · Recall",
            "Catch the target and build your score!",
            "⭐",
            "Study tip: Try recalling an idea before checking your notes."
        )
    )

    private var currentTipIndex = 0
    private var currentChallengeIndex = 0

    private val loadingTipRunnable = object : Runnable {
        override fun run() {
            if (_binding != null &&
                binding.aiLoadingCard.visibility == View.VISIBLE
            ) {
                currentTipIndex = (currentTipIndex + 1) % studyTips.size
                currentChallengeIndex =
                    (currentChallengeIndex + 1) % loadingChallenges.size

                val challenge = loadingChallenges[currentChallengeIndex]
                binding.tvAiLoadingTitle.text = challenge.title
                binding.tvAiLoadingSubtitle.text = challenge.subtitle
                binding.tvGameInstruction.text = challenge.instruction
                binding.tvLoadingStar.text = challenge.target
                binding.tvStudyTip.text = challenge.tip

                loadingHandler.postDelayed(this, 7000L)
            }
        }
    }

    companion object {
        private const val TAG = "LearnFragment"
        private const val PREFS_AUTO_SAVE = "learn_auto_save"
        private const val KEY_LAST_AUTO_SAVE_HASH = "last_auto_save_hash"
        private const val PREFS_SESSION_FILES = "study_session_files"
        private const val KEY_ACTIVE_FILE = "active.file"
        private const val KEY_ACTIVE_TYPE = "active.type"
        private const val KEY_ACTIVE_NAME = "active.name"

        // Maximum PDF size allowed before upload
        private const val MAX_PDF_SIZE_BYTES = 10L * 1024L * 1024L
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }

    private val pickPdfLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handlePdfSelected(it) }
    }

    private val takeCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                Log.d(TAG, "Photo captured: $uri")
                handleImageSelected(uri)
            }
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()
        } else {
            Toast.makeText(context, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this)[LearnViewModel::class.java]
        _binding = FragmentLearnBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupChatRecyclerView()
        setupTTS()
        setupUI()
        setupLoadingGame()
        observeViewModel()
        clearLearnSession()
    }

    private fun setupChatRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = chatAdapter
        }
    }

    private fun setupTTS() {
        ttsManager = TTSManager(requireContext()) { initialized ->
            binding.btnReadAloud.isEnabled = initialized
            if (!initialized) {
                Toast.makeText(context, "Text-to-speech not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupUI() {
        binding.btnLoadSavedNotes.setOnClickListener {
            // Capture the latest text/chat before showing the history list.
            val notes = binding.etNotes.text?.toString().orEmpty().trim()
            val summary = binding.tvSummary.text?.toString().orEmpty().trim()
            if (notes.isNotBlank() || summary.isNotBlank() || selectedPdfUri != null || selectedImageUri != null) {
                saveCurrentSessionToHistory(
                    notes,
                    summary,
                    generateTitle(summary, notes)
                )
            }
            showLoadSavedNotesDialog()
        }

        binding.btnUploadImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnTakePhoto.setOnClickListener {
            checkCameraPermission()
        }

        binding.btnUploadPdf.setOnClickListener {
            pickPdfLauncher.launch("application/pdf")
        }

        binding.btnRemoveImage.setOnClickListener {
            selectedImageUri = null
            clearActiveUpload()

            binding.imagePreviewCard.visibility = View.GONE
            binding.btnRemoveImage.visibility = View.GONE
            binding.imagePreview.setImageDrawable(null)

            binding.summaryCard.visibility = View.GONE
            binding.chatCard.visibility = View.GONE
            binding.tvSummary.text = ""

            Toast.makeText(
                context,
                "Image removed.",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnRemovePdf.setOnClickListener {
            selectedPdfUri = null
            clearActiveUpload()

            binding.pdfPreviewCard.visibility = View.GONE
            binding.tvSelectedPdf.text = ""
            binding.tvSelectedPdfSize.text = ""

            binding.summaryCard.visibility = View.GONE
            binding.chatCard.visibility = View.GONE
            binding.tvSummary.text = ""

            Toast.makeText(
                context,
                "PDF removed.",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnSummarize.setOnClickListener {
            val notes = binding.etNotes.text.toString()
            val language = if (binding.rbHindi.isChecked) "hi" else "en"
            when {
                selectedPdfUri != null -> {
                    viewModel.summarizePdfFromUri(requireContext(), selectedPdfUri!!, "brief", language)
                }
                selectedImageUri != null -> {
                    viewModel.summarizeImageFromUri(requireContext(), selectedImageUri!!, "brief", language)
                }
                notes.isNotEmpty() -> {
                    // Save current notes for Quiz/Flashcard tab
                    StudyDataManager.setCurrentNotes(requireContext(), notes, "")
                    viewModel.summarizeNotes(notes, "brief", language)
                }
                else -> {
                    Toast.makeText(context, "Please enter notes or select an image/PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnReadAloud.setOnClickListener {
            val summary = binding.tvSummary.text.toString()
            if (summary.isNotEmpty()) {
                val language = if (binding.rbHindi.isChecked) "hi" else "en"
                val languageApplied = ttsManager?.setLanguage(language) == true
                if (!languageApplied && language == "hi") {
                    Toast.makeText(context, "Hindi voice not available on this device. Falling back to English.", Toast.LENGTH_SHORT).show()
                }
                val started = ttsManager?.speak(summary) == true
                binding.btnStopReading.isEnabled = started
                if (!started) {
                    Toast.makeText(context, "Text-to-speech unavailable on this device", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnStopReading.setOnClickListener {
            ttsManager?.stop()
            binding.btnStopReading.isEnabled = false
        }

        binding.btnSendChat.setOnClickListener {
            val question = binding.etChatInput.text.toString().trim()
            val language = if (binding.rbHindi.isChecked) "hi" else "en"
            if (question.isNotEmpty()) {
                viewModel.sendChatMessage(question, language)
                binding.etChatInput.text?.clear()
            } else {
                Toast.makeText(context, "Please enter a question", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnNewChat.setOnClickListener {
            startNewSession()
        }

        binding.etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                binding.btnSendChat.performClick()
                true
            } else {
                false
            }
        }

        binding.btnSaveNotes.setOnClickListener {
            val notes = binding.etNotes.text.toString()
            val summary = binding.tvSummary.text.toString()
            if (notes.isNotEmpty() || summary.isNotEmpty()) {
                showSaveDialog(notes.ifBlank { summary }, summary)
            } else {
                Toast.makeText(context, "Please add notes before saving", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnMindMap.setOnClickListener {
            val notes = binding.etNotes.text.toString().ifBlank { binding.tvSummary.text.toString() }
            if (notes.isBlank()) {
                Toast.makeText(context, "Add or summarize notes first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val language = if (binding.rbHindi.isChecked) "hi" else "en"
            val intent = Intent(requireContext(), MindMapActivity::class.java).apply {
                putExtra(MindMapActivity.EXTRA_NOTES, notes.take(12000))
                putExtra(MindMapActivity.EXTRA_LANG, language)
            }
            startActivity(intent)
        }
    }

    private fun observeViewModel() {
        viewModel.summary.observe(viewLifecycleOwner) { summary ->
            binding.tvSummary.text = styleSummaryText(summary)
            binding.summaryCard.visibility = if (summary.isNotEmpty()) View.VISIBLE else View.GONE
            binding.chatCard.visibility = if (summary.isNotEmpty()) View.VISIBLE else View.GONE
            if (summary.isNotEmpty()) {
                // Update stored notes with fresh summary for Quiz tab
                val notes = binding.etNotes.text.toString().ifBlank { summary }
                StudyDataManager.setCurrentNotes(requireContext(), notes, summary)
                autoSaveSummaryIfNeeded(notes, summary)
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->

            binding.aiLoadingCard.visibility =
                if (isLoading) View.VISIBLE else View.GONE

            binding.btnSummarize.isEnabled = !isLoading
            binding.btnUploadImage.isEnabled = !isLoading
            binding.btnTakePhoto.isEnabled = !isLoading
            binding.btnUploadPdf.isEnabled = !isLoading
            binding.btnLoadSavedNotes.isEnabled = !isLoading

            if (isLoading) {
                gameScore = 0
                currentTipIndex = 0
                currentChallengeIndex = Random.nextInt(loadingChallenges.size)

                val challenge = loadingChallenges[currentChallengeIndex]

                binding.tvGameScore.text = "Score: 0 ⭐"
                binding.tvAiLoadingTitle.text = challenge.title
                binding.tvAiLoadingSubtitle.text = challenge.subtitle
                binding.tvGameInstruction.text = challenge.instruction
                binding.tvLoadingStar.text = challenge.target
                binding.tvStudyTip.text = challenge.tip
                binding.aiLoadingProgress.visibility = View.VISIBLE
                binding.snakeGame.start()

                // Place the target in the middle before moving it.
                binding.loadingGameArea.post {
                    val centerX =
                        (binding.loadingGameArea.width -
                                binding.tvLoadingStar.width) / 2f
                    val centerY =
                        (binding.loadingGameArea.height -
                                binding.tvLoadingStar.height) / 2f

                    binding.tvLoadingStar.setX(centerX)
                    binding.tvLoadingStar.setY(centerY)
                    moveLoadingStar()
                }

                loadingHandler.removeCallbacks(loadingTipRunnable)
                loadingHandler.postDelayed(loadingTipRunnable, 7000L)
            } else {
                loadingHandler.removeCallbacks(loadingTipRunnable)
                binding.snakeGame.stop()
            }
        }


        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            }
        }

        viewModel.chatMessages.observe(viewLifecycleOwner) { messages ->
            chatAdapter.submitList(messages)
            if (messages.isNotEmpty()) {
                binding.rvChat.smoothScrollToPosition(messages.size - 1)
            }
        }

        viewModel.chatLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.chatProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnSendChat.isEnabled = !isLoading
            binding.etChatInput.isEnabled = !isLoading
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                openCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(context, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun openCamera() {
        Log.d(TAG, "Opening camera")
        val intent = Intent(requireContext(), CameraActivity::class.java)
        takeCameraLauncher.launch(intent)
    }

    private fun handleImageSelected(uri: Uri) {
        Log.d(TAG, "Processing image: $uri")

        selectedImageUri = uri
        selectedPdfUri = null
        persistActiveUpload(uri, "image")

        // Hide PDF
        binding.pdfPreviewCard.visibility = View.GONE
        binding.btnRemovePdf.visibility = View.GONE
        binding.tvSelectedPdfSize.text = ""

        // Show image
        binding.imagePreviewCard.visibility = View.VISIBLE
        binding.btnRemoveImage.visibility = View.VISIBLE

        Glide.with(this)
            .load(uri)
            .into(binding.imagePreview)

        // Clear previous result
        binding.summaryCard.visibility = View.GONE
        binding.chatCard.visibility = View.GONE
        binding.tvSummary.text = ""

        Toast.makeText(
            context,
            "Image selected. Tap Summarize to analyze with AI.",
            Toast.LENGTH_SHORT
        ).show()
    }


    private fun handlePdfSelected(uri: Uri) {
        Log.d(TAG, "PDF selected: $uri")

        selectedPdfUri = uri
        selectedImageUri = null

        // Clear previous image
        binding.imagePreviewCard.visibility = View.GONE
        binding.btnRemoveImage.visibility = View.GONE
        binding.imagePreview.setImageDrawable(null)

        // Show PDF card
        val fileName = resolveDisplayName(uri)
        val fileSize = getFileSize(uri)

        if (fileSize > MAX_PDF_SIZE_BYTES) {
            val sizeText = formatFileSize(fileSize)

            Toast.makeText(
                context,
                "PDF too large ($sizeText). Maximum allowed size is 10 MB.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val sizeText = formatFileSize(fileSize)
        persistActiveUpload(uri, "pdf")

        binding.tvSelectedPdf.text =
            "📄  $fileName"
        binding.tvSelectedPdfSize.text = sizeText

        binding.pdfPreviewCard.visibility = View.VISIBLE

        // X button is already inside the card
        binding.btnRemovePdf.visibility = View.VISIBLE

        // IMPORTANT: remove old summary/chat state
        binding.summaryCard.visibility = View.GONE
        binding.chatCard.visibility = View.GONE
        binding.tvSummary.text = ""

        Log.d(TAG, "PDF ready for summarization: $fileName ($sizeText)")

        Toast.makeText(
            context,
            "PDF selected. Tap Summarize to analyze it.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun getFileSize(uri: Uri): Long {
        val cursor = requireContext().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )

        cursor?.use {
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)

            if (sizeIndex >= 0 && it.moveToFirst()) {
                return it.getLong(sizeIndex)
            }
        }

        // Fallback if the provider does not expose the size
        return requireContext().contentResolver
            .openAssetFileDescriptor(uri, "r")
            ?.use { it.length }
            ?: -1L
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"

        val kb = bytes / 1024.0

        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb)
        }

        val mb = kb / 1024.0

        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb)
        }

        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    private fun resolveDisplayName(uri: Uri): String {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null) ?: return "document.pdf"
        cursor.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) {
                return it.getString(nameIndex) ?: "document.pdf"
            }
        }
        return "document.pdf"
    }

    private fun showSaveDialog(notes: String, summary: String) {
        val titleInput = android.widget.EditText(requireContext()).apply {
            hint = "Enter a name for these notes"
            setSingleLine(true)
            setPadding(48, 24, 48, 24)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Save Notes")
            .setView(titleInput)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text.toString().trim()

                if (title.isBlank()) {
                    titleInput.error = "Please enter a name"
                    return@setOnClickListener
                }

                val chatHistory = viewModel.chatMessages.value.orEmpty().map {
                    com.padhai.app.data.ChatHistoryEntry(
                        it.role, it.content, it.timestamp
                    )
                }

                StudyDataManager.saveNote(
                    requireContext(), title, "General",
                    notes, summary, chatHistory
                )

                Toast.makeText(
                    context, "Saved as $title", Toast.LENGTH_SHORT
                ).show()

                dialog.dismiss()
                showLoadSavedNotesDialog()
            }
        }

        dialog.show()
    }

    private fun styleSummaryText(text: String): CharSequence {
        val builder = SpannableStringBuilder()
        val lines = text.replace("\r\n", "\n").split('\n')

        lines.forEachIndexed { index, rawLine ->
            // Remove Markdown heading markers so they do not appear in the UI.
            val line = rawLine.trimEnd().trim()
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^\\*\\*(.*)\\*\\*$"), "$1")

            if (line.isNotBlank()) {
                val isContentHeading =
                    line.equals("Content Summary and Key Concepts", ignoreCase = true)

                val isBullet =
                    line.startsWith("•") ||
                            line.matches(Regex("^[-*]\\s+.*")) ||
                            line.matches(Regex("^\\d+[.)]\\s+.*"))

                val looksLikeHeading = isContentHeading ||
                        (!isBullet && line.length <= 80 && (
                                line.endsWith(":") ||
                                        line.uppercase(Locale.US) == line ||
                                        line.matches(Regex("^[A-Z][A-Za-z0-9\\s&/()'-]{2,60}$"))
                                ))

                // Add a blank line before headings so they are separated from
                // the extracted/source text above them.
                if (looksLikeHeading && builder.isNotEmpty()) {
                    while (builder.isNotEmpty() && builder.last() == '\n') {
                        builder.delete(builder.length - 1, builder.length)
                    }
                    builder.append("\n\n")
                }

                val start = builder.length
                builder.append(line)
                val end = builder.length

                if (isContentHeading) {
                    builder.setSpan(
                        StyleSpan(android.graphics.Typeface.BOLD),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        RelativeSizeSpan(1.35f),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else if (looksLikeHeading) {
                    builder.setSpan(
                        StyleSpan(android.graphics.Typeface.BOLD),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        RelativeSizeSpan(1.18f),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else if (!isBullet && line.contains(":")) {
                    val colonIndex = line.indexOf(':')
                    if (colonIndex in 2..32) {
                        builder.setSpan(
                            StyleSpan(android.graphics.Typeface.BOLD),
                            start,
                            start + colonIndex + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }

            if (index < lines.lastIndex &&
                builder.isNotEmpty() &&
                builder.last() != '\n'
            ) {
                builder.append('\n')
            }
        }

        return builder
    }

    private fun generateTitle(summary: String, notes: String): String {
        val source = (summary.ifBlank { notes })
            .lines()
            .map { it.trim().trimStart('•', '-', '*').trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        if (source.isBlank()) return "Study Notes"

        return source
            .split(Regex("\\s+"))
            .take(6)
            .joinToString(" ")
            .replace(Regex("[^A-Za-z0-9\\s]"), "")
            .ifBlank { "Study Notes" }
    }

    private fun autoSaveSummaryIfNeeded(notes: String, summary: String) {
        val normalized = "${notes.trim()}|${summary.trim()}"
        if (normalized.isBlank()) return

        val hash = normalized.hashCode().toString()
        val prefs = UserScope.prefs(requireContext(), PREFS_AUTO_SAVE)
        val lastHash = prefs.getString(KEY_LAST_AUTO_SAVE_HASH, "")
        if (hash == lastHash) return

        val title = generateTitle(summary, notes)
        val contentToSave = notes.ifBlank { summary }

        // Convert chat messages to ChatHistoryEntry for saving
        val chatHistory = viewModel.chatMessages.value?.map {
            com.padhai.app.data.ChatHistoryEntry(it.role, it.content, it.timestamp)
        } ?: emptyList()

        viewModel.saveNotes(contentToSave, summary, title, "General")
        StudyDataManager.saveNote(requireContext(), title, "General", contentToSave, summary, chatHistory)
        prefs.edit().putString(KEY_LAST_AUTO_SAVE_HASH, hash).apply()
    }

    private fun activePrefs() = UserScope.prefs(requireContext(), PREFS_SESSION_FILES)

    private fun persistActiveUpload(sourceUri: Uri, type: String) {
        try {
            val resolver = requireContext().contentResolver
            val displayName = if (type == "pdf") resolveDisplayName(sourceUri) else "study_image.${when (resolver.getType(sourceUri)?.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }}"
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val directory = File(requireContext().filesDir, "study_sessions").apply { mkdirs() }
            val destination = File(directory, "active_${type}_$safeName")
            resolver.openInputStream(sourceUri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to read uploaded file")
            activePrefs().edit()
                .putString(KEY_ACTIVE_FILE, destination.absolutePath)
                .putString(KEY_ACTIVE_TYPE, type)
                .putString(KEY_ACTIVE_NAME, displayName)
                .apply()
            val durableUri = Uri.fromFile(destination)
            if (type == "pdf") selectedPdfUri = durableUri else selectedImageUri = durableUri
        } catch (e: Exception) {
            Log.e(TAG, "Unable to persist active upload", e)
            Toast.makeText(context, "The file was selected but could not be saved for tab switching.", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearActiveUpload() {
        activePrefs().edit().remove(KEY_ACTIVE_FILE).remove(KEY_ACTIVE_TYPE).remove(KEY_ACTIVE_NAME).apply()
    }

    private fun restoreActiveSession() {
        val notes = StudyDataManager.getCurrentNotes(requireContext())
        val summary = StudyDataManager.getCurrentSummary(requireContext())
        if (notes.isNotBlank()) binding.etNotes.setText(notes)
        if (summary.isNotBlank()) {
            binding.tvSummary.text = styleSummaryText(summary)
            binding.summaryCard.visibility = View.VISIBLE
            binding.chatCard.visibility = View.VISIBLE
            viewModel.setContextFromLoadedNotes(notes, summary)
            viewModel.setRestoredSummary(summary)
        }
        val prefs = activePrefs()
        val path = prefs.getString(KEY_ACTIVE_FILE, null) ?: return
        val file = File(path)
        if (!file.exists()) {
            clearActiveUpload()
            return
        }
        val uri = Uri.fromFile(file)
        val name = prefs.getString(KEY_ACTIVE_NAME, file.name).orEmpty()
        when (prefs.getString(KEY_ACTIVE_TYPE, "")) {
            "pdf" -> {
                selectedPdfUri = uri
                binding.tvSelectedPdf.text = "📄  $name"
                binding.tvSelectedPdfSize.text = formatFileSize(file.length())
                binding.pdfPreviewCard.visibility = View.VISIBLE
                binding.btnRemovePdf.visibility = View.VISIBLE
            }
            "image" -> {
                selectedImageUri = uri
                binding.imagePreviewCard.visibility = View.VISIBLE
                binding.btnRemoveImage.visibility = View.VISIBLE
                Glide.with(this).load(uri).into(binding.imagePreview)
            }
        }
    }


    private fun startNewSession() {
        if (viewModel.isLoading.value == true) {
            Toast.makeText(
                context,
                "Please wait until the current AI task finishes.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val notes = binding.etNotes.text.toString().trim()
        val summary = binding.tvSummary.text.toString().trim()
        val hasChat = viewModel.chatMessages.value.orEmpty()
            .any { !it.isConfirmation }

        val hasContent = notes.isNotBlank() ||
                summary.isNotBlank() ||
                selectedPdfUri != null ||
                selectedImageUri != null ||
                hasChat

        if (!hasContent) {
            clearLearnSession()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Save this session?")
            .setMessage("Would you like to save these notes before starting a new chat?")
            .setPositiveButton("Yes, save") { _, _ ->
                showSessionNameDialog(notes, summary) {
                    clearLearnSession()
                }
            }
            .setNegativeButton("No, new chat") { _, _ ->
                clearLearnSession()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun clearLearnSession() {
        selectedImageUri = null
        selectedPdfUri = null
        clearActiveUpload()
        binding.etNotes.setText("")
        binding.tvSummary.text = ""
        binding.etChatInput.text?.clear()

        binding.imagePreviewCard.visibility = View.GONE
        binding.imagePreview.setImageDrawable(null)
        binding.btnRemoveImage.visibility = View.GONE

        binding.pdfPreviewCard.visibility = View.GONE
        binding.tvSelectedPdf.text = ""
        binding.tvSelectedPdfSize.text = ""
        binding.btnRemovePdf.visibility = View.GONE

        binding.summaryCard.visibility = View.GONE
        binding.chatCard.visibility = View.GONE

        viewModel.clearCurrentSession()
        StudyDataManager.setCurrentNotes(requireContext(), "", "")
        UserScope.prefs(requireContext(), PREFS_AUTO_SAVE)
            .edit().remove(KEY_LAST_AUTO_SAVE_HASH).apply()

    }

    private fun showSessionNameDialog(
        notes: String,
        summary: String,
        onSaved: () -> Unit
    ) {
        val titleInput = android.widget.EditText(requireContext()).apply {
            hint = "Enter a name for this session"
            setSingleLine(true)
            setPadding(48, 24, 48, 24)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Name Your Study Session")
            .setMessage("Give this session a name so you can find it later.")
            .setView(titleInput)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val title = titleInput.text.toString().trim()

                    if (title.isBlank()) {
                        titleInput.error = "Please enter a session name"
                        return@setOnClickListener
                    }

                    // Save notes, summary, chat, and uploaded file under
                    // the user-provided session name.
                    saveCurrentSessionToHistory(
                        notes,
                        summary,
                        title
                    )

                    Toast.makeText(
                        context,
                        "Session saved as $title",
                        Toast.LENGTH_SHORT
                    ).show()

                    dialog.dismiss()
                    onSaved()
                }
        }

        dialog.show()
    }


    /** Saves the current study state locally before the screen is cleared. */
    private fun saveCurrentSessionToHistory(
        notes: String,
        summary: String,
        sessionTitle: String
    ) {
        val messages = viewModel.chatMessages.value.orEmpty()
        val chatHistory = messages.map {
            com.padhai.app.data.ChatHistoryEntry(it.role, it.content, it.timestamp)
        }

        val uploadedName = when {
            selectedPdfUri != null -> resolveDisplayName(selectedPdfUri!!)
            selectedImageUri != null -> "Image study session"
            else -> ""
        }
        val contentToSave = notes.ifBlank { summary }.ifBlank {
            if (uploadedName.isNotBlank()) "Uploaded material: $uploadedName" else "Study session"
        }
        val title = sessionTitle.trim().ifBlank {
            if (notes.isBlank() && summary.isBlank() && uploadedName.isNotBlank()) {
                uploadedName.substringBeforeLast('.', uploadedName)
                    .ifBlank { "Study Notes" }
            } else {
                generateTitle(summary, notes)
            }
        }

        // Reuse an already autosaved entry when possible to avoid adding a duplicate
        // every time New Chat is tapped. Otherwise create a history entry now.
        var savedNote = StudyDataManager.getNotesList(requireContext()).firstOrNull {
            it.title == title && it.content == contentToSave && it.summary == summary
        }

        if (savedNote == null) {
            StudyDataManager.saveNote(
                requireContext(), title, "General", contentToSave, summary, chatHistory
            )
            viewModel.saveNotes(contentToSave, summary, title, "General")
            savedNote = StudyDataManager.getNotesList(requireContext()).firstOrNull {
                it.title == title && it.content == contentToSave && it.summary == summary
            }
        }

        savedNote?.let { note ->
            val key = sessionStorageKey(note.title, note.date)
            val prefs = UserScope.prefs(requireContext(), PREFS_SESSION_FILES)
            prefs.edit()
                .putString("$key.chat", serializeChatHistory(chatHistory))
                .apply()

            // Copy the selected document into app-private storage. The original picker URI
            // may stop working after the app restarts, so keep a durable local copy.
            val sourceUri = selectedPdfUri ?: selectedImageUri
            if (sourceUri != null) {
                try {
                    val isPdf = selectedPdfUri != null
                    val originalName = if (isPdf) {
                        resolveDisplayName(sourceUri)
                    } else {
                        "study_image.${when (requireContext().contentResolver.getType(sourceUri)?.lowercase()) {
                            "image/png" -> "png"
                            "image/webp" -> "webp"
                            else -> "jpg"
                        }}"
                    }
                    val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val directory = File(requireContext().filesDir, "study_sessions").apply { mkdirs() }
                    val destination = File(directory, "${System.currentTimeMillis()}_$safeName")
                    requireContext().contentResolver.openInputStream(sourceUri)?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Unable to read uploaded file")

                    prefs.edit()
                        .putString("$key.file", destination.absolutePath)
                        .putString("$key.type", if (isPdf) "pdf" else "image")
                        .apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to preserve uploaded study file", e)
                    Toast.makeText(context, "Study notes were saved, but the original file could not be kept.", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Keep the quiz/flashcard tab synchronized with the active study content.
        StudyDataManager.setCurrentNotes(requireContext(), contentToSave, summary)
    }

    private fun sessionStorageKey(title: String, date: String): String =
        "session_${(title + "|" + date).hashCode()}"

    private fun serializeChatHistory(history: List<com.padhai.app.data.ChatHistoryEntry>): String {
        val array = org.json.JSONArray()
        history.forEach { item ->
            array.put(org.json.JSONObject().apply {
                put("role", item.role)
                put("content", item.content)
                put("timestamp", item.timestamp)
            })
        }
        return array.toString()
    }

    private fun restoreChatHistory(
        title: String,
        date: String,
        savedHistory: List<com.padhai.app.data.ChatHistoryEntry>,
        prefs: android.content.SharedPreferences
    ) {
        val stored = prefs.getString("${sessionStorageKey(title, date)}.chat", null)
        if (stored.isNullOrBlank()) {
            savedHistory.forEach { historyEntry ->
                viewModel.addChatMessageFromHistory(
                    com.padhai.app.data.model.ChatMessage(
                        role = historyEntry.role,
                        content = historyEntry.content,
                        timestamp = historyEntry.timestamp
                    )
                )
            }
            return
        }

        try {
            val array = org.json.JSONArray(stored)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                viewModel.addChatMessageFromHistory(
                    com.padhai.app.data.model.ChatMessage(
                        role = item.optString("role"),
                        content = item.optString("content"),
                        timestamp = item.optLong("timestamp")
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore saved chat history; using note history", e)
            savedHistory.forEach { historyEntry ->
                viewModel.addChatMessageFromHistory(
                    com.padhai.app.data.model.ChatMessage(
                        role = historyEntry.role,
                        content = historyEntry.content,
                        timestamp = historyEntry.timestamp
                    )
                )
            }
        }
    }

    private fun restoreSavedUpload(title: String, date: String) {
        selectedImageUri = null
        selectedPdfUri = null

        binding.imagePreviewCard.visibility = View.GONE
        binding.imagePreview.setImageDrawable(null)
        binding.btnRemoveImage.visibility = View.GONE
        binding.pdfPreviewCard.visibility = View.GONE
        binding.tvSelectedPdf.text = ""
        binding.tvSelectedPdfSize.text = ""
        binding.btnRemovePdf.visibility = View.GONE

        val key = sessionStorageKey(title, date)
        val prefs = UserScope.prefs(requireContext(), PREFS_SESSION_FILES)
        val path = prefs.getString("$key.file", null) ?: return
        val file = File(path)
        if (!file.exists()) return

        val uri = Uri.fromFile(file)
        when (prefs.getString("$key.type", "")) {
            "pdf" -> {
                selectedPdfUri = uri
                binding.tvSelectedPdf.text = "📄  ${file.name.substringAfter('_', file.name)}"
                binding.tvSelectedPdfSize.text = formatFileSize(file.length())
                binding.pdfPreviewCard.visibility = View.VISIBLE
                binding.btnRemovePdf.visibility = View.VISIBLE
            }
            "image" -> {
                selectedImageUri = uri
                binding.imagePreviewCard.visibility = View.VISIBLE
                binding.btnRemoveImage.visibility = View.VISIBLE
                Glide.with(this).load(uri).into(binding.imagePreview)
            }
        }
    }


    private fun showLoadSavedNotesDialog() {
        val savedNotes = StudyDataManager.getNotesList(requireContext())

        if (savedNotes.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Saved Notes")
                .setMessage(
                    "You haven't saved any notes yet. Add notes and generate a summary to save your first topic."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val items = savedNotes.map { note ->
            val title = note.title.ifBlank { "Untitled topic" }
            val date = note.date.take(16).replace('T', ' ')
            "$title\n$date\n"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Saved Notes")
            .setItems(items) { dialog, which ->
                dialog.dismiss()
                showSavedNoteDetails(savedNotes[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSavedNoteDetails(
        note: com.padhai.app.data.SavedNote
    ) {
        val context = requireContext()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }

        fun addSection(label: String, value: String) {
            val heading = TextView(context).apply {
                text = label
                textSize = 13f
                setTypeface(
                    typeface,
                    android.graphics.Typeface.BOLD
                )
                setPadding(0, dp(12), 0, dp(4))
            }

            content.addView(heading)

            val body = TextView(context).apply {
                text = value.ifBlank { "No content saved." }
                textSize = 15f
                setTextIsSelectable(true)
                setPadding(0, 0, 0, dp(4))
            }

            content.addView(body)
        }

        addSection(
            "TOPIC",
            note.title.ifBlank { "Untitled topic" }
        )

        if (note.content.isNotBlank()) {
            addSection("SAVED NOTES", note.content)
        }

        addSection("SUMMARY", note.summary)

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Saved Note Details")
            .setView(scrollView)
            .setPositiveButton("Open Note") { _, _ ->
                restoreSavedNote(note)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun restoreSavedNote(
        selected: com.padhai.app.data.SavedNote
    ) {
        binding.etNotes.setText(selected.content)

        binding.tvSummary.text =
            styleSummaryText(selected.summary)

        binding.summaryCard.visibility =
            if (selected.summary.isNotBlank()) View.VISIBLE
            else View.GONE

        binding.chatCard.visibility =
            if (selected.summary.isNotBlank()) View.VISIBLE
            else View.GONE

        binding.etChatInput.text?.clear()

        viewModel.clearCurrentSession()

        viewModel.setContextFromLoadedNotes(
            selected.content,
            selected.summary
        )

        restoreChatHistory(
            selected.title,
            selected.date,
            selected.chatHistory,
            UserScope.prefs(requireContext(), PREFS_SESSION_FILES)
        )

        restoreSavedUpload(selected.title, selected.date)

        StudyDataManager.setCurrentNotes(
            requireContext(),
            selected.content,
            selected.summary
        )

        Toast.makeText(
            context,
            "Opened: ${selected.title}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()


    private fun moveLoadingStar() {
        if (_binding == null) return

        val gameArea = binding.loadingGameArea
        val star = binding.tvLoadingStar

        gameArea.post {
            val maxX = (gameArea.width - star.width).coerceAtLeast(0)
            val maxY = (gameArea.height - star.height).coerceAtLeast(0)

            val minX = 12
            val minY = 45

            val x = if (maxX > minX) {
                Random.nextInt(minX, maxX.coerceAtLeast(minX + 1))
            } else {
                minX
            }

            val y = if (maxY > minY) {
                Random.nextInt(minY, maxY.coerceAtLeast(minY + 1))
            } else {
                minY
            }

            star.animate()
                .x(x.toFloat())
                .y(y.toFloat())
                .setDuration(250)
                .start()
        }
    }

    private fun setupLoadingGame() {

        binding.snakeGame.onScoreChanged = { score, best ->
            if (_binding != null) {
                binding.tvGameScore.text = "Swipe to merge | Score: $score | Best: $best"
            }
        }

        binding.tvLoadingStar.setOnClickListener {

            gameScore++

            binding.tvGameScore.text =
                "Score: $gameScore ⭐"

            binding.tvGameInstruction.text =
                "✨ Great catch! Find the target again!"

            binding.tvLoadingStar.animate()
                .scaleX(1.25f)
                .scaleY(1.25f)
                .setDuration(100)
                .withEndAction {
                    binding.tvLoadingStar.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                }
                .start()

            moveLoadingStar()
        }
    }

    override fun onDestroyView() {

        loadingHandler.removeCallbacks(
            loadingTipRunnable
        )

        ttsManager?.shutdown()

        super.onDestroyView()

        _binding = null
    }

}