package com.getdown.textrator

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Play Store build. Hosts the bubble as an accessibility overlay, reads exact on-screen text from the
 * accessibility tree and, on Android 11+, screenshots without a per-session consent dialog.
 * Nothing is read until the user taps the bubble, except a light character count for the "grow" hint.
 */
class TractorService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var controller: CaptureController? = null
    private var lastShotAt = 0L
    private var lastScanAt = 0L
    private var lastShotError = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        controller = CaptureController(
            this, wm, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            readNodes = {
                val pkg = try { rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" }
                NodeRead(TextMerge.collectNodes(this, Screen.bounds(this)), pkg)
            },
            takeShot = { screenshot() },
            onClose = { Prefs.bubbleEnabled = false; refreshBubble() },
        )
        if (Prefs.bubbleEnabled) controller?.bubble?.show()
        Backend.ping()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val c = controller ?: return
        if (event == null || !Prefs.smartExpand) return
        if (!c.bubble.isShowing || c.selection != null || c.capturing) return
        if (event.packageName?.toString() == packageName) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScanAt < 1500) return
        lastScanAt = now
        val chars = try { TextMerge.countChars(rootInActiveWindow, 700) } catch (_: Exception) { 0 }
        c.bubble.setHint(chars >= 600)
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onScreenChanged()
    }

    override fun onDestroy() {
        instance = null
        controller?.destroy()
        controller = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        controller?.destroy()
        controller = null
        return super.onUnbind(intent)
    }

    fun refreshBubble() { controller?.bubble?.refresh() }

    fun capture() { controller?.capture() }

    private suspend fun screenshot(): Shot {
        if (Build.VERSION.SDK_INT < 30) return Shot(null, "Reading text inside images needs Android 11 or newer.")
        val bmp = shot()
        return if (bmp != null) Shot(bmp, null)
        else Shot(
            null,
            if (lastShotError == SECURE_WINDOW) "This app blocks screenshots. Showing its readable text only."
            else "Couldn't read images on this screen. Showing readable text only."
        )
    }

    @RequiresApi(30)
    private suspend fun shot(): Bitmap? {
        // Android allows about one accessibility screenshot per second.
        val wait = 1100 - (SystemClock.uptimeMillis() - lastShotAt)
        if (wait > 0) delay(wait)
        var bmp = shotOnce()
        if (bmp == null && lastShotError == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            delay(1100); bmp = shotOnce()
        }
        return bmp
    }

    @RequiresApi(30)
    private suspend fun shotOnce(): Bitmap? = suspendCancellableCoroutine { cont ->
        lastShotAt = SystemClock.uptimeMillis()
        lastShotError = 0
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = try {
                        // ML Kit can't read HARDWARE bitmaps; copy to a normal one.
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Exception) { null } finally { hb.close() }
                    if (cont.isActive) cont.resume(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    lastShotError = errorCode
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    fun dismissShadeThenCapture() {
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        else performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        handler.postDelayed({ capture() }, 650)
    }

    companion object {
        private const val SECURE_WINDOW = 6 // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34)

        @Volatile
        var instance: TractorService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, TractorService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
