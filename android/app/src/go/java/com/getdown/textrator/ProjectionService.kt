package com.getdown.textrator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.delay

/**
 * Go (website APK) build. No accessibility service, so Play Protect does not block the install.
 * The user approves screen capture once when the bubble starts. The capture keeps running in this
 * foreground service and each bubble tap just reads the latest frame. No new consent popup per tap.
 */
class ProjectionService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bg: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val lock = Any()
    private var latest: Image? = null
    private var capW = 0
    private var capH = 0
    private var controller: CaptureController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bgThread = HandlerThread("textrator-capture").also { it.start() }
        bg = Handler(bgThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_CAPTURE) { main.postDelayed({ controller?.capture() }, 500); return START_NOT_STICKY }

        // Android 14+: must be in the foreground with type mediaProjection BEFORE getMediaProjection().
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else legacyData(intent)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try { mpm.getMediaProjection(code, data) } catch (_: Exception) { null }
        if (p == null) { stopSelf(); return START_NOT_STICKY }
        projection = p
        // Required before createVirtualDisplay on Android 14+. Also fires when the user taps "Stop sharing".
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        if (!createDisplay()) { stopSelf(); return START_NOT_STICKY }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        controller = CaptureController(
            this, wm, Screen.appOverlayType(),
            readNodes = { NodeRead(emptyList(), "screen") },
            takeShot = { grab() },
        )
        Prefs.bubbleEnabled = true
        controller?.bubble?.show()
        instance = this
        Backend.ping()
        return START_NOT_STICKY
    }

    private fun createDisplay(): Boolean {
        val b = Screen.bounds(this)
        capW = b.width(); capH = b.height()
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir -> keepLatest(ir) }, bg)
        reader = r
        display = try {
            projection?.createVirtualDisplay(
                "GETDOWN Textrator", capW, capH, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bg
            )
        } catch (_: Exception) { null }
        return display != null
    }

    /** Keeps only the newest frame; nothing is copied until the user taps the bubble. */
    private fun keepLatest(ir: ImageReader) {
        val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return
        synchronized(lock) {
            latest?.close()
            latest = img
        }
    }

    /** Rotation / resolution change: point the mirror at a new, correctly sized surface. */
    private fun resizeIfNeeded() {
        val b = Screen.bounds(this)
        if (b.width() == capW && b.height() == capH) return
        val d = display ?: return
        capW = b.width(); capH = b.height()
        val old = reader
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir -> keepLatest(ir) }, bg)
        reader = r
        d.resize(capW, capH, resources.displayMetrics.densityDpi)
        d.surface = r.surface
        synchronized(lock) { latest?.close(); latest = null }
        old?.close()
    }

    private suspend fun grab(): Shot {
        resizeIfNeeded()
        delay(220) // a fresh frame without the bubble in it
        var bmp: Bitmap? = null
        for (attempt in 0 until 3) {
            bmp = synchronized(lock) { latest?.let { toBitmap(it) } }
            if (bmp != null) break
            delay(200)
        }
        return if (bmp != null) Shot(bmp, null)
        else Shot(null, "Couldn't capture the screen. Tap Stop and Start bubble again.")
    }

    private fun toBitmap(img: Image): Bitmap? = try {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        if (full.width == img.width) full
        else Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
    } catch (_: Exception) { null }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onScreenChanged()
    }

    override fun onDestroy() {
        instance = null
        controller?.destroy(); controller = null
        display?.release(); display = null
        synchronized(lock) { latest?.close(); latest = null }
        reader?.close(); reader = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        bgThread.quitSafely()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun refreshBubble() { controller?.bubble?.refresh() }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bubble", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the Textrator bubble is on"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("GETDOWN Textrator is on")
            .setContentText("Tap the bubble on any screen to copy its text.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun legacyData(intent: Intent?): Intent? = intent?.getParcelableExtra(EXTRA_DATA) as? Intent

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.getdown.textrator.STOP"
        const val ACTION_CAPTURE = "com.getdown.textrator.CAPTURE"
        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: ProjectionService? = null
            private set

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ProjectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
