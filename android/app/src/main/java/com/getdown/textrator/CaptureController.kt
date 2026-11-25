package com.getdown.textrator

import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a screen grab returned: a bitmap, or a message explaining why not. */
class Shot(val bitmap: Bitmap?, val notice: String?)

/** Exact text from the accessibility tree (empty in the Go build) plus the app it came from. */
class NodeRead(val blocks: List<TextBlock>, val sourcePkg: String)

/**
 * The capture pipeline shared by both builds:
 * hide bubble → read text nodes (Play build) → grab screen → OCR → merge → selection layer.
 */
class CaptureController(
    private val ctx: Context,
    private val wm: WindowManager,
    private val windowType: Int,
    private val readNodes: suspend () -> NodeRead,
    private val takeShot: suspend () -> Shot,
) {
    private val scope = MainScope()
    val bubble = BubbleController(ctx, wm, windowType) { capture() }
    var selection: SelectionOverlay? = null
        private set
    var capturing = false
        private set

    fun capture() {
        if (capturing || selection != null) return
        capturing = true
        bubble.setBusy(true)
        bubble.hideForCapture()
        scope.launch {
            var shown = false
            try {
                delay(160) // let the bubble leave the frame before the screenshot
                val nodes = withContext(Dispatchers.Default) {
                    try { readNodes() } catch (_: Exception) { NodeRead(emptyList(), "") }
                }
                var ocr: List<TextBlock> = emptyList()
                var notice: String? = null
                if (Prefs.useOcr || nodes.blocks.isEmpty()) {
                    val shot = try { takeShot() } catch (_: Exception) { Shot(null, "Couldn't capture the screen.") }
                    val bmp = shot.bitmap
                    if (bmp != null) {
                        ocr = try { OcrEngine.recognize(InputImage.fromBitmap(bmp, 0)) } catch (_: Exception) { emptyList() }
                        bmp.recycle()
                    } else notice = shot.notice
                }
                bubble.restoreAfterCapture()

                val blocks = TextMerge.merge(nodes.blocks, ocr)
                val method = when {
                    ocr.isNotEmpty() && nodes.blocks.isNotEmpty() -> "mixed"
                    ocr.isNotEmpty() -> "ocr"
                    else -> "text"
                }
                Backend.logEvent("capture", blocks.sumOf { it.text.length }, nodes.sourcePkg, method)

                if (blocks.isEmpty()) {
                    toast(notice ?: "No text found on this screen")
                } else {
                    bubble.hide()
                    val sel = SelectionOverlay(ctx, wm, windowType, blocks, nodes.sourcePkg, method, notice) {
                        selection = null
                        if (Prefs.bubbleEnabled) bubble.show()
                    }
                    selection = sel
                    sel.show()
                    shown = true
                }
            } catch (e: Exception) {
                toast("Something went wrong: ${e.message ?: "unknown error"}")
            } finally {
                capturing = false
                bubble.setBusy(false)
                if (!shown) bubble.restoreAfterCapture()
            }
        }
    }

    fun onScreenChanged() {
        bubble.onScreenChanged()
        selection?.close() // boxes would be in the wrong place after rotation
    }

    fun destroy() {
        selection?.close()
        bubble.hide()
        scope.cancel()
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
