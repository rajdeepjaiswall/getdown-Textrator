package com.getdown.textrator

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** One selectable piece of text on screen, in absolute screen pixels. */
data class TextBlock(val text: String, val rect: Rect, val fromOcr: Boolean)

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
     * Accessibility text is exact, so it wins. OCR only fills the gaps: text inside images,
     * videos, canvases, games and apps that hide their text from accessibility.
     */
    fun merge(nodes: List<TextBlock>, ocr: List<TextBlock>): List<TextBlock> {
        val out = ArrayList<TextBlock>(nodes)
        for (o in ocr) {
            val covered = nodes.any { n -> overlap(n.rect, o.rect) > 0.45f }
            if (!covered) out.add(o)
        }
        return readingOrder(dedupe(out))
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

    /**
     * Top-to-bottom, left-to-right. Uses a stable bucket key instead of a "same line" comparator,
     * because fuzzy comparators break TimSort ("Comparison method violates its general contract").
     */
    fun readingOrder(list: List<TextBlock>): List<TextBlock> =
        list.sortedWith(compareBy<TextBlock>({ it.rect.centerY() / 24 }, { it.rect.left }))

    fun join(list: List<TextBlock>): String = readingOrder(list).joinToString("\n") { it.text.trim() }

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
