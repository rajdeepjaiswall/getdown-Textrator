package com.getdown.textrator

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * One selectable piece of text on screen (usually a single word), in absolute screen pixels.
 * [line] groups words that sit on the same line; [order] is the reading order across the screen.
 */
data class TextBlock(
    val text: String,
    val rect: Rect,
    val fromOcr: Boolean,
    val line: Int = -1,
    val order: Int = 0,
)

object TextMerge {

    private fun area(r: Rect) = r.width().toLong().coerceAtLeast(0) * r.height().toLong().coerceAtLeast(0)

    /** Intersection area divided by the smaller rect's area (0..1). */
    fun overlap(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val small = minOf(area(a), area(b))
        return if (small <= 0) 0f else area(i).toFloat() / small
    }

    /**
     * Builds the word boxes shown to the user.
     * - Single-line accessibility text is exact, so it wins; it's split into words.
     * - OCR fills the rest (images, videos, canvases, apps that hide text) with real per-word boxes.
     * - A multi-line accessibility block is replaced by OCR words when OCR saw it (exact word positions),
     *   otherwise it stays one block.
     */
    fun merge(nodes: List<TextBlock>, ocr: List<OcrLine>): List<TextBlock> {
        val clean = dedupe(nodes)
        val lineH = ocr.map { it.rect.height() }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val maxSingle = if (lineH > 0) (lineH * 1.9f).toInt() else 140
        val single = clean.filter { !it.text.contains('\n') && it.rect.height() <= maxSingle }
        val multi = clean.filter { it !in single }

        // Each source line → list of words, before ordering.
        val lines = ArrayList<Pair<Rect, List<Pair<String, Rect>>>>()
        for (n in single) lines.add(Rect(n.rect) to splitWords(n.text, n.rect))
        for (m in multi) {
            val seenByOcr = ocr.any { overlap(it.rect, m.rect) > 0.5f }
            if (!seenByOcr) lines.add(Rect(m.rect) to listOf(m.text to Rect(m.rect)))
        }
        for (o in ocr) {
            val covered = single.any { n -> overlap(n.rect, o.rect) > 0.45f }
            if (!covered) lines.add(Rect(o.rect) to o.words)
        }

        val ordered = lines.sortedWith(compareBy({ it.first.centerY() / 24 }, { it.first.left }))
        val out = ArrayList<TextBlock>()
        var order = 0
        ordered.forEachIndexed { li, (_, words) ->
            for ((t, r) in words.sortedBy { it.second.left }) {
                out.add(TextBlock(t, r, true, li, order++))
            }
        }
        return out
    }

    /** Splits one line of exact text into words, estimating each word's box from its share of characters. */
    private fun splitWords(text: String, r: Rect): List<Pair<String, Rect>> {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size <= 1) return listOf(text.trim() to Rect(r))
        val totalChars = parts.sumOf { it.length } + (parts.size - 1)
        val pxPerChar = r.width().toFloat() / totalChars
        var x = r.left.toFloat()
        return parts.map { p ->
            val w = p.length * pxPerChar
            val box = Rect(x.toInt(), r.top, (x + w).toInt().coerceAtMost(r.right), r.bottom)
            x += w + pxPerChar
            p to box
        }
    }

    private fun dedupe(list: List<TextBlock>): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        for (b in list) {
            val dup = out.any { it.text == b.text && overlap(it.rect, b.rect) > 0.6f }
            if (!dup) out.add(b)
        }
        // Drop a container block when its text is just its children's text joined together.
        return out.filter { parent ->
            val kids = out.filter { it !== parent && Rect(parent.rect).contains(it.rect) }
            !(kids.size >= 2 && kids.sumOf { it.text.length } >= parent.text.length * 0.8)
        }
    }

    /** Selected words back to text: spaces within a line, new lines between lines, reading order. */
    fun join(list: List<TextBlock>): String {
        val sb = StringBuilder()
        var prevLine = Int.MIN_VALUE
        for (w in list.sortedBy { it.order }) {
            if (sb.isNotEmpty()) sb.append(if (w.line == prevLine) " " else "\n")
            sb.append(w.text.trim())
            prevLine = w.line
        }
        return sb.toString()
    }

    /** Reads text that apps expose to accessibility, across every visible window except our own overlays. */
    fun collectNodes(service: AccessibilityService, screen: Rect, maxNodes: Int = 2500): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in service.windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                w.root?.let { roots.add(it) }
            }
        } catch (_: Exception) { }
        if (roots.isEmpty()) service.rootInActiveWindow?.let { roots.add(it) }
        val counter = intArrayOf(0)
        for (r in roots) walk(r, out, screen, 0, counter, maxNodes)
        return out
    }

    private fun walk(
        n: AccessibilityNodeInfo, out: MutableList<TextBlock>, screen: Rect,
        depth: Int, counter: IntArray, max: Int,
    ) {
        if (depth > 80 || counter[0] > max) return
        counter[0]++
        if (!n.isVisibleToUser) return
        if (!n.isPassword) {
            var t: CharSequence? = n.text
            if (t.isNullOrBlank() && n.childCount == 0) t = n.contentDescription
            if (!t.isNullOrBlank()) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.intersect(screen) && r.width() > 2 && r.height() > 2) {
                    out.add(TextBlock(t.toString().trim(), r, false))
                }
            }
        }
        for (i in 0 until n.childCount) {
            val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
            walk(c, out, screen, depth + 1, counter, max)
        }
    }

    /** Cheap scan used by the bubble's "lots of text here" hint. */
    fun countChars(root: AccessibilityNodeInfo?, limit: Int): Int {
        if (root == null) return 0
        var total = 0
        var visited = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && total < limit && visited < 600) {
            val n = stack.removeLast()
            visited++
            if (!n.isVisibleToUser) continue
            n.text?.let { total += it.length }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Exception) { null }
                if (c != null) stack.addLast(c)
            }
        }
        return total
    }
}
