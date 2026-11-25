package com.getdown.textrator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Invisible helper activity.
 *  - default: turn the bubble on (display over other apps + notification). Does NOT ask for screen access.
 *  - EXTRA_REQUEST_ACCESS: bubble was tapped with no open session → ask Android once, then read the screen.
 *  - EXTRA_CAPTURE_NOW: Quick Settings tile → closes the shade, then behaves like a bubble tap.
 */
class CaptureStarterActivity : AppCompatActivity() {

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startBubble() }

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ProjectionService::class.java)
                    .setAction(ProjectionService.ACTION_GRANTED)
                    .putExtra(ProjectionService.EXTRA_CODE, res.resultCode)
                    .putExtra(ProjectionService.EXTRA_DATA, data)
            )
        } else {
            Toast.makeText(this, "Textrator needs screen access to read text. Tap the bubble to try again.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Engine.isReady(this)) {
            Toast.makeText(this, "Allow “Display over other apps” for GETDOWN Textrator, then come back.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) { OemHelper.openAppInfo(this) }
            finish(); return
        }
        when {
            intent.getBooleanExtra(EXTRA_CAPTURE_NOW, false) -> {
                val svc = ProjectionService.instance
                if (svc != null && svc.hasScreenAccess) {
                    startService(Intent(this, ProjectionService::class.java).setAction(ProjectionService.ACTION_CAPTURE))
                    finish()
                } else {
                    ContextCompat.startForegroundService(this, Intent(this, ProjectionService::class.java).setAction(ProjectionService.ACTION_SHOW))
                    askAccess()
                }
            }
            intent.getBooleanExtra(EXTRA_REQUEST_ACCESS, false) -> askAccess()
            else -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else startBubble()
            }
        }
    }

    private fun startBubble() {
        ContextCompat.startForegroundService(this, Intent(this, ProjectionService::class.java).setAction(ProjectionService.ACTION_SHOW))
        Toast.makeText(this, "Bubble is on. Tap it on any screen to copy text.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun askAccess() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14+: force "Entire screen" so the bubble works in every app, not just one.
        val i = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        try { capture.launch(i) } catch (_: Exception) { finish() }
    }

    companion object {
        const val EXTRA_CAPTURE_NOW = "capture_now"
        const val EXTRA_REQUEST_ACCESS = "request_access"
    }
}
