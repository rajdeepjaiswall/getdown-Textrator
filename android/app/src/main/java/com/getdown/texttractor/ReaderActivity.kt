package com.getdown.texttractor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

/**
 * Shows text with real Android selection handles so any exact word or line can be copied.
 * Also receives: photos shared from other apps (OCR), and text from the "Text Tractor" option in
 * other apps' text-selection menu, which gets saved to history (the "inbox" behaviour).
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var edit: EditText
    private var source = ""
    private var method = "text"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        edit = findViewById(R.id.text)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val title = findViewById<TextView>(R.id.title)

        source = intent.getStringExtra(EXTRA_SOURCE) ?: (referrer?.host ?: "")
        method = intent.getStringExtra(EXTRA_METHOD) ?: "text"

        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
        val processed = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val image: Uri? = intent.data ?: imageFromShare()

        when {
            intent.hasExtra(EXTRA_TEXT) -> edit.setText(intent.getStringExtra(EXTRA_TEXT))
            processed != null -> {
                edit.setText(processed); method = "share"
                ClipHelper.save(this, processed, source, method)
                toast("Saved to Text Tractor")
            }
            image != null -> {
                title.text = "Reading photo…"
                method = "image"
                progress.visibility = View.VISIBLE
                edit.visibility = View.GONE
                lifecycleScope.launch {
                    val text = try {
                        OcrEngine.fullText(OcrEngine.recognize(InputImage.fromFilePath(this@ReaderActivity, image)))
                    } catch (e: Exception) { "" }
                    progress.visibility = View.GONE
                    edit.visibility = View.VISIBLE
                    title.text = if (text.isBlank()) "No text found in this photo" else "Text from photo"
                    edit.setText(text)
                    if (text.isNotBlank()) Backend.logEvent("capture", text.length, "photo", "image")
                }
            }
            shared != null -> {
                edit.setText(shared); method = "share"
                ClipHelper.save(this, shared, source, method)
                toast("Saved to Text Tractor")
            }
        }

        findViewById<Button>(R.id.close).setOnClickListener { finish() }
        findViewById<Button>(R.id.share).setOnClickListener { ClipHelper.share(this, edit.text.toString()) }
        findViewById<Button>(R.id.copyAll).setOnClickListener {
            val fromHistory = intent.getBooleanExtra(EXTRA_FROM_HISTORY, false)
            ClipHelper.copy(this, edit.text.toString(), source, if (fromHistory) "history" else method)
            finish()
        }
        findViewById<Button>(R.id.copySel).setOnClickListener {
            val s = edit.selectionStart; val e = edit.selectionEnd
            if (s < 0 || e <= s) { toast("Long-press the text and drag the handles to select words first."); return@setOnClickListener }
            ClipHelper.copy(this, edit.text.substring(s, e), source, method)
            finish()
        }
    }

    private fun imageFromShare(): Uri? {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_TEXT = "tt_text"
        const val EXTRA_SOURCE = "tt_source"
        const val EXTRA_METHOD = "tt_method"
        const val EXTRA_FROM_HISTORY = "tt_from_history"
    }
}
