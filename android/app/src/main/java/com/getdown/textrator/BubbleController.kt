package com.getdown.textrator

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The floating, see-through bubble. Hosted by the accessibility service as an
 * accessibility overlay, so it needs no separate "draw over other apps" permission
 * and survives as long as the service is on.
 * Tap = capture, drag = move (snaps to edge), long-press = open the app.
 */
class BubbleController(private val ctx: Context, private val wm: WindowManager, private val onTap: () -> Unit) {

    private var root: FrameLayout? = null
    private var icon: ImageView? = null
    private var badge: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hinting = false
    private var busy = false

    val isShowing: Boolean get() = root != null

    private val idleAlpha: Float get() = (Prefs.bubbleOpacity.coerceIn(25, 100)) / 100f

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val size = Screen.dp(ctx, Prefs.bubbleSizeDp.coerceIn(40, 80).toFloat())
        val pad = (size * 0.18f).toInt() // room to grow when expanding
        val box = size + pad * 2

        val frame = FrameLayout(ctx)
        val iv = ImageView(ctx).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Rounded-square shadow that matches the logo's shape.
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.18f)
                }
            }
            elevation = Screen.dp(ctx, 4f).toFloat()
            contentDescription = "GETDOWN Textrator: tap to copy text from the screen"
        }
        frame.addView(iv, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        val b = View(ctx).apply {
            setBackgroundResource(R.drawable.bubble_badge)
            visibility = View.GONE
            elevation = Screen.dp(ctx, 6f).toFloat()
        }
        val bs = (size * 0.3f).toInt()
        frame.addView(b, FrameLayout.LayoutParams(bs, bs, Gravity.TOP or Gravity.END).apply {
            topMargin = pad / 2; rightMargin = pad / 2
        })
        frame.alpha = idleAlpha

        val screen = Screen.bounds(ctx)
        val lp = WindowManager.LayoutParams(
            box, box,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (Prefs.bubbleX >= 0) Prefs.bubbleX else screen.width() - box
            y = if (Prefs.bubbleY >= 0) Prefs.bubbleY else (screen.height() * 0.38f).toInt()
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        clamp(lp, box, screen.width(), screen.height())

        frame.setOnTouchListener(TouchHandler(frame, lp, box))

        try {
            wm.addView(frame, lp)
        } catch (e: Exception) {
            return
        }
        root = frame; icon = iv; badge = b; params = lp
        hinting = false
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null; icon = null; badge = null; params = null
    }

    fun refresh() { hide(); if (Prefs.bubbleEnabled) show() }

    /** INVISIBLE keeps the window but draws nothing, so the bubble never appears in its own screenshot. */
    fun hideForCapture() { root?.visibility = View.INVISIBLE }
    fun restoreAfterCapture() { root?.visibility = View.VISIBLE }

    fun setBusy(b: Boolean) {
        busy = b
        val r = root ?: return
        r.animate().alpha(if (b) 1f else if (hinting) 0.95f else idleAlpha).setDuration(150).start()
    }

    /** Expands a little and shows a brown dot when the screen holds a lot of text. */
    fun setHint(on: Boolean) {
        if (on == hinting || busy) return
        hinting = on
        val iv = icon ?: return
        val r = root ?: return
        badge?.visibility = if (on) View.VISIBLE else View.GONE
        iv.animate().scaleX(if (on) 1.15f else 1f).scaleY(if (on) 1.15f else 1f)
            .setInterpolator(OvershootInterpolator()).setDuration(260).start()
        r.animate().alpha(if (on) 0.95f else idleAlpha).setDuration(260).start()
    }

    fun onScreenChanged() {
        val lp = params ?: return
        val r = root ?: return
        val s = Screen.bounds(ctx)
        clamp(lp, lp.width, s.width(), s.height())
        runCatching { wm.updateViewLayout(r, lp) }
    }

    private fun clamp(lp: WindowManager.LayoutParams, box: Int, w: Int, h: Int) {
        lp.x = lp.x.coerceIn(0, (w - box).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(Screen.dp(ctx, 24f), (h - box - Screen.dp(ctx, 24f)).coerceAtLeast(0))
    }

    private inner class TouchHandler(
        private val v: View, private val lp: WindowManager.LayoutParams, private val box: Int,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            if (Prefs.haptics) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragging = false; longPressed = false
                    v.animate().alpha(1f).setDuration(100).start()
                    handler.postDelayed(longPress, 650)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) { dragging = true; handler.removeCallbacks(longPress) }
                    if (dragging) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    v.animate().alpha(if (hinting) 0.95f else idleAlpha).setDuration(200).start()
                    if (dragging) snapToEdge(v)
                    else if (!longPressed && e.actionMasked == MotionEvent.ACTION_UP) {
                        if (Prefs.haptics) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onTap()
                    }
                }
            }
            return true
        }

        private fun snapToEdge(v: View) {
            val s = Screen.bounds(ctx)
            clamp(lp, box, s.width(), s.height())
            val target = if (lp.x + box / 2 < s.width() / 2) 0 else s.width() - box
            val from = lp.x
            if (abs(target - from) < 2) { save(); return }
            ValueAnimator.ofInt(from, target).apply {
                duration = 220
                addUpdateListener {
                    lp.x = it.animatedValue as Int
                    if (root != null) runCatching { wm.updateViewLayout(v, lp) }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { save() }
                })
                start()
            }
        }

        private fun save() { Prefs.bubbleX = lp.x; Prefs.bubbleY = lp.y }
    }
}
