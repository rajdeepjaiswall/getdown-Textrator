package com.getdown.texttractor

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
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
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Heart of the app. Hosts the bubble and the selection layer, reads on-screen text
 * (accessibility tree) and, on Android 11+, takes a screenshot without any extra
 * consent dialog to OCR text inside images. Nothing is read until the user taps the bubble,
 * except a lightweight character count used to make the bubble "expand" on text-heavy screens.
 */
class TractorService : AccessibilityService() {

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var bubble: BubbleController? = null
    private var selection: SelectionOverlay? = null
    private var capturing = false
    private var lastShotAt = 0L
    private var lastScanAt = 0L
    private var lastShotError = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bubble = BubbleController(this, wm) { capture() }
        if (Prefs.bubbleEnabled) bubble?.show()
        Backend.ping()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !Prefs.smartExpand) return
        val b = bubble ?: return
        if (!b.isShowing || selection != null || capturing) return
        if (event.packageName?.toString() == packageName) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScanAt < 1500) return
        lastScanAt = now
        val chars = try { TextMerge.countChars(rootInActiveWindow, 700) } catch (_: Exception) { 0 }
        b.setHint(chars >= 600)
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.onScreenChanged()
        selection?.close() // boxes would be in the wrong place after rotation
    }

    override fun onDestroy() {
        instance = null
        selection?.close()
        bubble?.hide()
        scope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        bubble?.hide()
        return super.onUnbind(intent)
    }

    fun refreshBubble() {
        bubble?.refresh()
    }

    /** Entry point for bubble taps and the Quick Settings tile. */
    fun capture() {
        if (capturing || selection != null) return
        capturing = true
        val b = bubble
        b?.setBusy(true)
        b?.hideForCapture()
        scope.launch {
            var shown = false
            try {
                delay(160) // let the bubble leave the frame before the screenshot
                val screen = Screen.bounds(this@TractorService)
                val sourcePkg = try { rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" }
                val nodes = withContext(Dispatchers.Default) {
                    try { TextMerge.collectNodes(this@TractorService, screen) } catch (_: Exception) { emptyList() }
                }

                var ocr: List<TextBlock> = emptyList()
                var notice: String? = null
                if (Prefs.useOcr) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val bmp = shot()
                        if (bmp != null) {
                            ocr = try { OcrEngine.recognize(InputImage.fromBitmap(bmp, 0)) } catch (_: Exception) { emptyList() }
                            bmp.recycle()
                        } else {
                            notice = if (lastShotError == SECURE_WINDOW) "This app blocks screenshots. Showing its readable text only."
                            else "Couldn't read images on this screen. Showing readable text only."
                        }
                    }
                }
                b?.restoreAfterCapture()

                val blocks = TextMerge.merge(nodes, ocr)
                val method = when {
                    ocr.isNotEmpty() && nodes.isNotEmpty() -> "mixed"
                    ocr.isNotEmpty() -> "ocr"
                    else -> "text"
                }
                Backend.logEvent("capture", blocks.sumOf { it.text.length }, sourcePkg, method)

                if (blocks.isEmpty()) {
                    toast(notice ?: "No text found on this screen")
                } else {
                    b?.hide()
                    val sel = SelectionOverlay(this@TractorService, wm, blocks, sourcePkg, method, notice) {
                        selection = null
                        if (Prefs.bubbleEnabled) bubble?.show()
                    }
                    selection = sel
                    sel.show()
                    shown = true
                }
            } catch (e: Exception) {
                toast("Something went wrong: ${e.message ?: "unknown error"}")
            } finally {
                capturing = false
                b?.setBusy(false)
                if (!shown) b?.restoreAfterCapture()
            }
        }
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
