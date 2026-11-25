package com.getdown.textrator

import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** One recognised line and its individual words, all in image pixels. */
class OcrLine(val text: String, val rect: Rect, val words: List<Pair<String, Rect>>)

/**
 * On-device OCR (free, offline, no API key). Runs the Latin and Devanagari models and keeps
 * the Devanagari line wherever the Latin model would otherwise output garbage for Hindi text.
 * Returns word-level boxes so the user can select single words, like normal copy-paste.
 */
object OcrEngine {
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val deva by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    private fun hasDevanagari(s: String) = s.any { it in 'ऀ'..'ॿ' }

    suspend fun recognize(image: InputImage): List<OcrLine> {
        val lat = runCatching { latin.process(image).await() }.getOrNull()
        val dev = runCatching { deva.process(image).await() }.getOrNull()
        val latLines = lines(lat)
        val devLines = lines(dev).filter { hasDevanagari(it.text) }
        val keptLatin = latLines.filter { l -> devLines.none { d -> TextMerge.overlap(l.rect, d.rect) > 0.4f } }
        return keptLatin + devLines
    }

    /** Plain text of an image, in reading order (used for photos shared to the app). */
    suspend fun recognizeText(image: InputImage): String =
        TextMerge.join(TextMerge.merge(emptyList(), recognize(image)))

    private fun lines(t: Text?): List<OcrLine> {
        if (t == null) return emptyList()
        val out = ArrayList<OcrLine>()
        for (b in t.textBlocks) for (l in b.lines) {
            val r = l.boundingBox ?: continue
            val s = l.text.trim()
            if (s.isEmpty()) continue
            val words = l.elements.mapNotNull { e ->
                val er = e.boundingBox ?: return@mapNotNull null
                val et = e.text.trim()
                if (et.isEmpty()) null else et to Rect(er)
            }
            out.add(OcrLine(s, Rect(r), words.ifEmpty { listOf(s to Rect(r)) }))
        }
        return out
    }
}
