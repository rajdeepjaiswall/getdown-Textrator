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
 * Invisible helper that walks through the permissions needed to start the bubble:
 * display over other apps → notifications (Android 13+) → screen capture consent.
 * With EXTRA_CAPTURE_NOW it just closes the Quick Settings shade and captures.
 */
class CaptureStarterActivity : AppCompatActivity() {

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askCapture() }

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ProjectionService::class.java)
                .putExtra(ProjectionService.EXTRA_CODE, res.resultCode)
                .putExtra(ProjectionService.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, i)
            Toast.makeText(this, "Bubble is on. Tap it on any screen.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Screen capture is needed to read text. Tap Start bubble to try again.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_CAPTURE_NOW, false) && ProjectionService.instance != null) {
            startService(Intent(this, ProjectionService::class.java).setAction(ProjectionService.ACTION_CAPTURE))
            finish(); return
        }
        if (ProjectionService.instance != null) { finish(); return }
        if (!Engine.isReady(this)) {
            Toast.makeText(this, "Allow “Display over other apps” for GETDOWN Textrator, then come back.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) { OemHelper.openAppInfo(this) }
            finish(); return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14+: force "Entire screen" so the bubble works in every app, not just one.
        val intent = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        try { capture.launch(intent) } catch (_: Exception) { finish() }
    }

    companion object { const val EXTRA_CAPTURE_NOW = "capture_now" }
}
