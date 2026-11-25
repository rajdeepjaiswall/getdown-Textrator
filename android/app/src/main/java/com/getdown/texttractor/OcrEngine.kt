package com.getdown.texttractor

import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * On-device OCR (free, offline, no API key). Runs the Latin and Devanagari models and keeps
 * the Devanagari line wherever the Latin model would otherwise output garbage for Hindi text.
 */
object OcrEngine {
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val deva by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    private fun hasDevanagari(s: String) = s.any { it in 'ऀ'..'ॿ' }

    suspend fun recognize(image: InputImage): List<TextBlock> {
        val lat = runCatching { latin.process(image).await() }.getOrNull()
        val dev = runCatching { deva.process(image).await() }.getOrNull()
        val latLines = lines(lat)
        val devLines = lines(dev).filter { hasDevanagari(it.text) }
        val keptLatin = latLines.filter { l -> devLines.none { d -> TextMerge.overlap(l.rect, d.rect) > 0.4f } }
        return keptLatin + devLines
    }

    private fun lines(t: Text?): List<TextBlock> {
        if (t == null) return emptyList()
        val out = ArrayList<TextBlock>()
        for (b in t.textBlocks) for (l in b.lines) {
            val r = l.boundingBox ?: continue
            val s = l.text.trim()
            if (s.isNotEmpty()) out.add(TextBlock(s, Rect(r), true))
        }
        return out
    }

    fun fullText(blocks: List<TextBlock>): String = TextMerge.join(blocks)
}
