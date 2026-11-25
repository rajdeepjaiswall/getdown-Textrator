package com.getdown.textrator

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager

object Screen {
    @Suppress("DEPRECATION")
    fun bounds(ctx: Context): Rect {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            Rect(wm.currentWindowMetrics.bounds)
        } else {
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            Rect(0, 0, p.x, p.y)
        }
    }

    /** Overlay type for apps holding "display over other apps". */
    @Suppress("DEPRECATION")
    fun appOverlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
