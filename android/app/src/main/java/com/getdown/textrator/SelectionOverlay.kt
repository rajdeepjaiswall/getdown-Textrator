package com.getdown.textrator

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen, see-through layer with a small bracket around every word, exactly where it sits.
 * Works like normal copy-paste:
 *  - tap a word to select / unselect it
 *  - drag from one word to another to select everything in between (spaces included)
 *  - drag starting on empty space draws a box and selects every word it touches
 *  - long-press a word to select its whole line
 */
class SelectionOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val windowType: Int,
    blocks: List<TextBlock>,
    private val sourcePkg: String,
    private val method: String,
    private val notice: String?,
    private val onClosed: () -> Unit,
) {
    /** Words in reading order: index == position in the text. */
    private val words: List<TextBlock> = blocks.sortedBy { it.order }
    private var root: FrameLayout? = null
    private lateinit var canvasView: WordsView
    private lateinit var copyBtn: TextView
    private lateinit var allBtn: TextView
    private lateinit var hint: TextView
    private val selected = BooleanArray(words.size)
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false

    private fun dp(v: Float) = Screen.dp(ctx, v)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val frame = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) close()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        frame.isFocusableInTouchMode = true
        canvasView = WordsView(ctx)
        frame.addView(canvasView, FrameLayout.LayoutParams(-1, -1))

        hint = pill(0xF2FFFFFF.toInt(), 0xFF171717.toInt()).apply { textSize = 13f }
        frame.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(48f)
        })

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.toolbar_bg)
            elevation = dp(8f).toFloat()
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
        allBtn = action("Select all") { toggleAll() }
        copyBtn = action("Copy all", primary = true) { copy() }
        bar.addView(allBtn)
        bar.addView(action("Edit") { openReader() })
        bar.addView(action("Share") { share() })
        bar.addView(copyBtn)
        bar.addView(action("✕") { close() })
        frame.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(64f)
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(frame, lp)
        } catch (e: Exception) {
            onClosed(); return
        }
        root = frame
        frame.requestFocus()
        updateUi()
        notice?.let { n -> hint.text = n; handler.postDelayed({ updateUi() }, 3500) }
    }

    fun close() {
        if (closed) return
        closed = true
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        onClosed()
    }

    private fun selectedWords(): List<TextBlock> {
        val s = words.filterIndexed { i, _ -> selected[i] }
        return s.ifEmpty { words }
    }

    private fun copy() {
        val text = TextMerge.join(selectedWords())
        ClipHelper.copy(ctx, text, sourcePkg, method, toast = false)
        hint.text = "✓ Copied: " + text.replace('\n', ' ').take(60)
        handler.postDelayed({ close() }, 700)
    }

    private fun share() {
        val text = TextMerge.join(selectedWords())
        ClipHelper.save(ctx, text, sourcePkg, method)
        close()
        ClipHelper.share(ctx, text)
    }

    private fun openReader() {
        val text = TextMerge.join(selectedWords())
        close()
        ctx.startActivity(
            Intent(ctx, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_TEXT, text)
                .putExtra(ReaderActivity.EXTRA_SOURCE, sourcePkg)
                .putExtra(ReaderActivity.EXTRA_METHOD, method)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    private fun toggleAll() {
        val all = selected.all { it }
        selected.fill(!all)
        updateUi()
    }

    private fun updateUi() {
        val n = selected.count { it }
        copyBtn.text = if (n == 0) "Copy all" else "Copy"
        allBtn.text = if (n == words.size && n > 0) "Clear" else "Select all"
        if (!closed) {
            hint.text = if (n == 0) "Tap a word or drag across words · ${words.size} words"
            else "“" + TextMerge.join(selectedWords()).replace('\n', ' ').take(48) + "”"
        }
        canvasView.invalidate()
    }

    private fun pill(bg: Int, fg: Int) = TextView(ctx).apply {
        setTextColor(fg)
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(bg) }
        elevation = dp(6f).toFloat()
        maxLines = 1
        maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.86f).toInt()
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun action(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        setTextColor(0xFF171717.toInt())
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        background = GradientDrawable().apply {
            cornerRadius = dp(22f).toFloat()
            setColor(if (primary) 0xFFA5E420.toInt() else Color.TRANSPARENT)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(2f); marginEnd = dp(2f) }
    }

    /** Draws the word brackets. Words are in screen pixels; the view's own screen offset is subtracted. */
    private inner class WordsView(c: Context) : View(c) {
        private val scrim = Paint().apply { color = 0x1A000000 }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26A5E420; style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC6EB90C.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1f).toFloat()
        }
        private val selFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8CA5E420.toInt(); style = Paint.Style.FILL }
        private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF171717.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.5f).toFloat()
        }
        private val marquee = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB74012.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.5f).toFloat()
            pathEffect = DashPathEffect(floatArrayOf(dp(6f).toFloat(), dp(4f).toFloat()), 0f)
        }
        private val loc = IntArray(2)
        private val r = RectF()
        private val r2 = RectF()
        private val radius = dp(3f).toFloat()
        private val pad = dp(1.5f).toFloat()
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private val catchRadius = dp(28f).toFloat()

        private var downX = 0f; private var downY = 0f
        private var curX = 0f; private var curY = 0f
        private var dragging = false
        private var anchor = -1          // word where a range drag started
        private var marqueeMode = false  // drag started on empty space
        private var baseSel = BooleanArray(words.size)
        private var longPressed = false
        private val longPress = Runnable {
            val i = hit(downX, downY)
            if (i >= 0) {
                longPressed = true
                val line = words[i].line
                words.forEachIndexed { k, w -> if (w.line == line) selected[k] = true }
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                updateUi()
            }
        }

        private fun rectOf(b: TextBlock, out: RectF) {
            getLocationOnScreen(loc)
            out.set(
                b.rect.left - loc[0] - pad, b.rect.top - loc[1] - pad,
                b.rect.right - loc[0] + pad, b.rect.bottom - loc[1] + pad
            )
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
            for (i in words.indices) {
                rectOf(words[i], r)
                if (selected[i]) {
                    // Join neighbouring selected words on the same line so the space is highlighted too,
                    // like a normal text selection.
                    if (i > 0 && selected[i - 1] && words[i - 1].line == words[i].line) {
                        rectOf(words[i - 1], r2)
                        canvas.drawRect(r2.right - radius, max(r.top, r2.top), r.left + radius, min(r.bottom, r2.bottom), selFill)
                    }
                    canvas.drawRoundRect(r, radius, radius, selFill)
                    canvas.drawRoundRect(r, radius, radius, selStroke)
                } else {
                    canvas.drawRoundRect(r, radius, radius, fill)
                    canvas.drawRoundRect(r, radius, radius, stroke)
                }
            }
            if (dragging && marqueeMode) {
                canvas.drawRect(min(downX, curX), min(downY, curY), max(downX, curX), max(downY, curY), marquee)
            }
        }

        /** Word under the finger (smallest box), with a little slack for tiny text. */
        private fun hit(x: Float, y: Float): Int {
            val slack = dp(6f).toFloat()
            var best = -1; var bestArea = Float.MAX_VALUE
            for (i in words.indices) {
                rectOf(words[i], r)
                r.inset(-slack, -slack)
                if (r.contains(x, y)) {
                    val a = r.width() * r.height()
                    if (a < bestArea) { bestArea = a; best = i }
                }
            }
            return best
        }

        /** Closest word to the finger while dragging, so the selection follows smoothly between words. */
        private fun nearest(x: Float, y: Float): Int {
            var best = -1; var bestD = Float.MAX_VALUE
            for (i in words.indices) {
                rectOf(words[i], r)
                val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
                val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
                val d = dx * dx + dy * dy * 4f // prefer the word on the same line
                if (d < bestD) { bestD = d; best = i }
            }
            return best
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; curX = e.x; curY = e.y
                    dragging = false; longPressed = false
                    baseSel = selected.copyOf()
                    anchor = hit(e.x, e.y)
                    if (anchor < 0) {
                        val n = nearest(e.x, e.y)
                        if (n >= 0) {
                            rectOf(words[n], r)
                            if (hypot(r.centerX() - e.x, r.centerY() - e.y) < catchRadius + r.width() / 2) anchor = n
                        }
                    }
                    marqueeMode = anchor < 0
                    handler.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    curX = e.x; curY = e.y
                    if (!dragging && hypot(curX - downX, curY - downY) > slop) {
                        dragging = true; handler.removeCallbacks(longPress)
                    }
                    if (dragging && !longPressed) {
                        if (marqueeMode) {
                            val m = RectF(min(downX, curX), min(downY, curY), max(downX, curX), max(downY, curY))
                            for (i in words.indices) {
                                rectOf(words[i], r)
                                selected[i] = baseSel[i] || RectF.intersects(m, r)
                            }
                        } else {
                            // Range selection in reading order, exactly like dragging a text-selection handle.
                            val to = nearest(curX, curY)
                            if (to >= 0) {
                                val a = min(anchor, to); val b = max(anchor, to)
                                for (i in words.indices) selected[i] = baseSel[i] || i in a..b
                            }
                        }
                        updateUi()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!dragging && !longPressed) {
                        val i = hit(e.x, e.y)
                        if (i >= 0) {
                            selected[i] = !selected[i]
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            updateUi()
                        }
                    }
                    dragging = false
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPress); dragging = false; invalidate() }
            }
            return true
        }
    }
}
