package com.getdown.textrator

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Play Store build: powered by the accessibility service. */
object Engine {
    const val USES_ACCESSIBILITY = true

    /** Has the one-time system permission been granted? */
    fun isReady(ctx: Context): Boolean = TractorService.isEnabled(ctx)

    fun isBubbleOn(): Boolean = TractorService.instance != null && Prefs.bubbleEnabled

    fun refreshBubble() { TractorService.instance?.refreshBubble() }

    fun setBubble(activity: Activity, on: Boolean) {
        if (on && !isReady(activity)) {
            activity.startActivity(Intent(activity, SetupActivity::class.java)); return
        }
        Prefs.bubbleEnabled = on
        refreshBubble()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    fun onTileClick(tile: TileService) {
        val svc = TractorService.instance
        if (svc != null) { svc.dismissShadeThenCapture(); return }
        val intent = Intent(tile, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            tile.startActivityAndCollapse(
                PendingIntent.getActivity(tile, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            tile.startActivityAndCollapse(intent)
        }
    }

    fun isTileActive(): Boolean = TractorService.instance != null
}
