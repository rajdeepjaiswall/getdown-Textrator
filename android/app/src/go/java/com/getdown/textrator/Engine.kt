package com.getdown.textrator

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService

/** Go build (website APK): overlay + screen capture. No accessibility service. */
object Engine {
    const val USES_ACCESSIBILITY = false

    fun isReady(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun isBubbleOn(): Boolean = ProjectionService.instance != null

    fun refreshBubble() { ProjectionService.instance?.refreshBubble() }

    fun setBubble(activity: Activity, on: Boolean) {
        if (on) activity.startActivity(Intent(activity, CaptureStarterActivity::class.java))
        else ProjectionService.stop(activity)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    fun onTileClick(tile: TileService) {
        // Launching an activity is what closes the shade; it then captures (or starts the bubble).
        val intent = Intent(tile, CaptureStarterActivity::class.java)
            .putExtra(CaptureStarterActivity.EXTRA_CAPTURE_NOW, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            tile.startActivityAndCollapse(
                PendingIntent.getActivity(tile, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            tile.startActivityAndCollapse(intent)
        }
    }

    fun isTileActive(): Boolean = ProjectionService.instance != null
}
