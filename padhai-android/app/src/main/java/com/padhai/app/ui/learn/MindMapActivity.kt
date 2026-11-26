package com.padhai.app.ui.learn

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.padhai.app.BuildConfig
import com.padhai.app.databinding.ActivityMindMapBinding
import com.padhai.app.utils.UserScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shows an AI generated mind map of the user's notes.
 * The result is cached per user, so opening the same notes again is instant
 * and does not use the AI quota.
 */
class MindMapActivity : AppCompatActivity() {

    private class MapData(val title: String, val branches: List<MindMapView.Branch>)

    private lateinit var binding: ActivityMindMapBinding

    private var notes = ""
    private var lang = "en"

    private val emulatorBaseUrl = BuildConfig.BACKEND_BASE_URL.trimEnd('/')
    private val phoneBaseUrl = BuildConfig.PHONE_BACKEND_BASE_URL.trim().trimEnd('/')

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMindMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        notes = intent.getStringExtra(EXTRA_NOTES).orEmpty()
        lang = intent.getStringExtra(EXTRA_LANG) ?: "en"

        binding.btnBack.setOnClickListener { finish() }
        binding.btnFit.setOnClickListener { binding.mindMapView.fitToScreen() }
        binding.btnRegenerate.setOnClickListener { load(forceRefresh = true) }
        binding.btnRetry.setOnClickListener { load(forceRefresh = true) }

        if (notes.isBlank()) {
            showError("No notes found. Add or summarize some notes in Learn first.")
            return
        }
        load(forceRefresh = false)
    }

    private fun load(forceRefresh: Boolean) {
        showLoading()

        val prefs = UserScope.prefs(this, PREFS_CACHE)
        val cacheKey = "mm_${lang}_${notes.hashCode()}"

        if (!forceRefresh) {
            val cached = prefs.getString(cacheKey, null)
            if (cached != null) {
                val parsed = parseMindMap(cached)
                if (parsed != null) {
                    showMap(parsed)
                    return
                }
            }
        }

        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { fetchMindMap() }
                val parsed = parseMindMap(json)
                    ?: throw RuntimeException("AI returned an invalid mind map. Please try again.")
                prefs.edit().putString(cacheKey, json).apply()
                showMap(parsed)
            } catch (e: Exception) {
                showError("Could not create the mind map: ${e.message}")
            }
        }
    }

    private fun fetchMindMap(): String {
        val url = URL("${resolveBaseUrl()}/api/notes/mindmap")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 120000

        val body = JSONObject()
            .put("notes", notes)
            .put("lang", lang)
            .toString()
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
        if (code !in 200..299) {
            throw RuntimeException("Server error ($code): ${text.take(300)}")
        }
        return text
    }

    private fun parseMindMap(json: String): MapData? {
        return try {
            val root = JSONObject(json.trim())
            val map = root.optJSONObject("mindmap") ?: root
            val title = map.optString("title", "Mind Map")
            val arr = map.optJSONArray("branches") ?: JSONArray()

            val branches = ArrayList<MindMapView.Branch>()
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val bTitle = b.optString("title").trim()
                if (bTitle.isEmpty()) continue
                val childArr = b.optJSONArray("children")
                val kids = ArrayList<String>()
                if (childArr != null) {
                    for (j in 0 until childArr.length()) {
                        val s = childArr.optString(j).trim()
                        if (s.isNotEmpty()) kids.add(s)
                    }
                }
                branches.add(MindMapView.Branch(bTitle, kids))
            }
            if (branches.isEmpty()) null else MapData(title, branches)
        } catch (e: Exception) {
            null
        }
    }

    private fun showLoading() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE
        binding.mindMapView.visibility = View.INVISIBLE
    }

    private fun showError(message: String) {
        binding.layoutLoading.visibility = View.GONE
        binding.mindMapView.visibility = View.INVISIBLE
        binding.layoutError.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun showMap(data: MapData) {
        binding.layoutLoading.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.mindMapView.visibility = View.VISIBLE
        binding.mindMapView.setData(data.title, data.branches)
    }

    private fun resolveBaseUrl(): String {
        return if (isEmulator()) emulatorBaseUrl else phoneBaseUrl.ifBlank { emulatorBaseUrl }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true)
    }

    companion object {
        const val EXTRA_NOTES = "extra_notes"
        const val EXTRA_LANG = "extra_lang"
        private const val PREFS_CACHE = "mindmap_cache"
    }
}
