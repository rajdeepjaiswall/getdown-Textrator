package com.getdown.textrator

import android.animation.AnimatorListenerAdapter
import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The floating, see-through bubble (the Getdown logo).
 * Play build: accessibility overlay. Go build: app overlay ("display over other apps").
 * Tap = read the screen · drag = move (snaps to the edge) · drag onto ✕ = switch off · long-press = open the app.
 * It grows a little every time it reads text and slowly shrinks back when idle.
 */
class BubbleController(
    private val ctx: Context,
    private val wm: WindowManager,
    private val windowType: Int,
    private val onTap: () -> Unit,
    private val onClose: () -> Unit,
) {
    private var root: FrameLayout? = null
    private var icon: ImageView? = null
    private var badge: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var closeTarget: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hinting = false
    private var busy = false
    private var growth = 0 // number of recent reads, 0..MAX_GROWTH

    val isShowing: Boolean get() = root != null

    private val idleAlpha: Float get() = (Prefs.bubbleOpacity.coerceIn(25, 100)) / 100f

    private fun targetScale(): Float = (1f + growth * 0.07f) * (if (hinting) 1.12f else 1f)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val size = Screen.dp(ctx, Prefs.bubbleSizeDp.coerceIn(MIN_DP, MAX_DP).toFloat())
        val pad = (size * 0.28f).toInt() // room to grow
        val box = size + pad * 2

        val frame = FrameLayout(ctx)
        val iv = ImageView(ctx).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.18f)
                }
            }
            elevation = Screen.dp(ctx, 4f).toFloat()
            contentDescription = "GETDOWN Textrator: tap to copy text from the screen"
            scaleX = targetScale(); scaleY = targetScale()
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
            box, box, windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Default spot: tucked in the lower-right corner, out of the way.
            x = if (Prefs.bubbleX >= 0) Prefs.bubbleX else screen.width() - box
            y = if (Prefs.bubbleY >= 0) Prefs.bubbleY else (screen.height() * 0.72f).toInt()
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        clamp(lp, box, screen.width(), screen.height())
        frame.setOnTouchListener(TouchHandler(frame, lp, box))

        try { wm.addView(frame, lp) } catch (e: Exception) { return }
        root = frame; icon = iv; badge = b; params = lp
    }

    fun hide() {
        hideCloseTarget()
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

    /** Play build only: expands a little and shows a rust dot when the screen holds a lot of text. */
    fun setHint(on: Boolean) {
        if (on == hinting || busy) return
        hinting = on
        badge?.visibility = if (on) View.VISIBLE else View.GONE
        animateTo(targetScale())
        root?.animate()?.alpha(if (on) 0.95f else idleAlpha)?.setDuration(260)?.start()
    }

    /** Called after every successful read: a happy pop, and the bubble stays a little bigger for a while. */
    fun celebrate() {
        growth = (growth + 1).coerceAtMost(MAX_GROWTH)
        val iv = icon ?: return
        val t = targetScale()
        iv.animate().scaleX(t + 0.2f).scaleY(t + 0.2f).setDuration(140).withEndAction {
            iv.animate().scaleX(t).scaleY(t).setInterpolator(OvershootInterpolator(3f)).setDuration(320).start()
        }.start()
        if (Prefs.haptics) iv.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        handler.removeCallbacks(shrink)
        handler.postDelayed(shrink, SHRINK_AFTER_MS)
    }

    private val shrink = Runnable {
        growth = 0
        animateTo(targetScale())
    }

    private fun animateTo(scale: Float) {
        icon?.animate()?.scaleX(scale)?.scaleY(scale)?.setInterpolator(OvershootInterpolator())?.setDuration(300)?.start()
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

    // ---------- drag onto ✕ to switch the bubble off ----------

    private fun showCloseTarget() {
        if (closeTarget != null) return
        val s = Screen.dp(ctx, 56f)
        val tv = TextView(ctx).apply {
            text = "✕"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE6B74012.toInt()) }
            elevation = Screen.dp(ctx, 6f).toFloat()
            alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            s, s, windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = Screen.dp(ctx, 72f)
        }
        try { wm.addView(tv, lp); closeTarget = tv; tv.animate().alpha(1f).setDuration(150).start() } catch (_: Exception) { }
    }

    private fun hideCloseTarget() {
        closeTarget?.let { runCatching { wm.removeView(it) } }
        closeTarget = null
    }

    /** Is the bubble's centre over the ✕ (bottom centre of the screen)? */
    private fun overCloseTarget(lp: WindowManager.LayoutParams, box: Int): Boolean {
        val s = Screen.bounds(ctx)
        val cx = lp.x + box / 2f; val cy = lp.y + box / 2f
        val tx = s.width() / 2f; val ty = s.height() - Screen.dp(ctx, 72f + 28f).toFloat()
        return hypot(cx - tx, cy - ty) < Screen.dp(ctx, 70f)
    }

    private inner class TouchHandler(
        private val v: View, private val lp: WindowManager.LayoutParams, private val box: Int,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false
        private var longPressed = false
        private var armed = false
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
                    dragging = false; longPressed = false; armed = false
                    v.animate().alpha(1f).setDuration(100).start()
                    handler.postDelayed(longPress, 650)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true; handler.removeCallbacks(longPress); showCloseTarget()
                    }
                    if (dragging) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                        val over = overCloseTarget(lp, box)
                        if (over != armed) {
                            armed = over
                            if (over && Prefs.haptics) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            closeTarget?.animate()?.scaleX(if (over) 1.3f else 1f)?.scaleY(if (over) 1.3f else 1f)?.setDuration(120)?.start()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    hideCloseTarget()
                    v.animate().alpha(if (hinting) 0.95f else idleAlpha).setDuration(200).start()
                    if (dragging && armed && e.actionMasked == MotionEvent.ACTION_UP) {
                        onClose(); return true
                    }
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
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { save() }
                })
                start()
            }
        }

        private fun save() { Prefs.bubbleX = lp.x; Prefs.bubbleY = lp.y }
    }

    companion object {
        const val MIN_DP = 28
        const val MAX_DP = 72
        private const val MAX_GROWTH = 4
        private const val SHRINK_AFTER_MS = 45_000L
    }
}
