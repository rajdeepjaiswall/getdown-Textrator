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
 *
 * - The bubble lives here and stays on screen even when screen access is off. No prompt on app start.
 * - Screen access is asked for only when you tap the bubble and there's no open session.
 * - Once allowed, the session stays open but PAUSED: the mirror has no surface, so no frames are
 *   produced. A tap attaches the surface, grabs ONE frame, then pauses again. Nothing is captured 24/7.
 * - Android ends the session when the phone locks or when you tap Stop sharing. The next tap asks again.
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

    val hasScreenAccess: Boolean get() = projection != null && display != null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bgThread = HandlerThread("textrator-capture").also { it.start() }
        bg = Handler(bgThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLOSE -> { closeBubble(); return START_NOT_STICKY }
            ACTION_STOP_ACCESS -> { endSession(); updateNotification(); return START_NOT_STICKY }
            ACTION_CAPTURE -> { main.postDelayed({ tap() }, 450); return START_NOT_STICKY }
            ACTION_GRANTED -> { onGranted(intent); return START_NOT_STICKY }
        }
        // ACTION_SHOW (default): bubble only, no screen access yet.
        if (!goForeground(withProjection = false)) { stopSelf(); return START_NOT_STICKY }
        ensureBubble()
        return START_NOT_STICKY
    }

    private fun ensureBubble() {
        if (controller == null) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            controller = CaptureController(
                this, wm, Screen.appOverlayType(),
                readNodes = { NodeRead(emptyList(), "screen") },
                takeShot = { grab() },
                onClose = { closeBubble() },
                onTap = { tap() },
            )
        }
        Prefs.bubbleEnabled = true
        controller?.bubble?.show()
        instance = this
        Backend.ping()
    }

    /** Bubble tapped: read now if screen access is open, otherwise ask for it once and then read. */
    fun tap() {
        if (hasScreenAccess) { controller?.capture(); return }
        startActivity(
            Intent(this, CaptureStarterActivity::class.java)
                .putExtra(CaptureStarterActivity.EXTRA_REQUEST_ACCESS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun onGranted(intent: Intent) {
        // Android 14+: be a mediaProjection foreground service BEFORE calling getMediaProjection().
        if (!goForeground(withProjection = true)) return
        ensureBubble()
        endSession()
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else legacyData(intent)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try { if (data != null) mpm.getMediaProjection(code, data) else null } catch (_: Exception) { null }
        if (p == null) { goForeground(withProjection = false); return }
        projection = p
        // Required before createVirtualDisplay on Android 14+. Fires on lock screen / "Stop sharing" too.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { endSession(); goForeground(withProjection = false) } }
        }, main)
        if (!createDisplay()) { endSession(); goForeground(withProjection = false); return }
        updateNotification()
        // Access is only ever requested from a tap, so read the screen straight away.
        main.postDelayed({ controller?.capture() }, 600) // let the permission dialog disappear first
    }

    /** One virtual display per session (Android rule). Created paused: no surface = no frames. */
    private fun createDisplay(): Boolean {
        val b = Screen.bounds(this)
        capW = b.width(); capH = b.height()
        reader = newReader()
        display = try {
            projection?.createVirtualDisplay(
                "GETDOWN Textrator", capW, capH, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, bg
            )
        } catch (_: Exception) { null }
        return display != null
    }

    private fun newReader(): ImageReader =
        ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ ir ->
                val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                synchronized(lock) { latest?.close(); latest = img }
            }, bg)
        }

    /** Grabs exactly one fresh frame, then pauses the mirror again. */
    private suspend fun grab(): Shot {
        val d = display ?: return Shot(null, "Screen access ended. Tap the bubble again.")
        val b = Screen.bounds(this)
        if (b.width() != capW || b.height() != capH) { // rotated
            capW = b.width(); capH = b.height()
            reader?.close(); reader = newReader()
            d.resize(capW, capH, resources.displayMetrics.densityDpi)
        }
        synchronized(lock) { latest?.close(); latest = null }
        d.surface = reader?.surface // resume: the next composed frame lands in the reader
        var bmp: Bitmap? = null
        try {
            for (i in 0 until 15) { // up to ~1.2 s for the first frame
                delay(80)
                bmp = synchronized(lock) { latest?.let { toBitmap(it) } }
                if (bmp != null) break
            }
        } finally {
            d.surface = null // pause again: nothing is captured until the next tap
            synchronized(lock) { latest?.close(); latest = null }
        }
        return if (bmp != null) Shot(bmp, null) else Shot(null, "Couldn't read the screen. Please try again.")
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

    private fun endSession() {
        display?.release(); display = null
        synchronized(lock) { latest?.close(); latest = null }
        reader?.close(); reader = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) { }
    }

    private fun closeBubble() {
        Prefs.bubbleEnabled = false
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onScreenChanged()
    }

    override fun onDestroy() {
        instance = null
        controller?.destroy(); controller = null
        endSession()
        bgThread.quitSafely()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun refreshBubble() { controller?.bubble?.refresh() }

    // ---------- foreground service + notification ----------

    private fun goForeground(withProjection: Boolean): Boolean {
        val type = when {
            Build.VERSION.SDK_INT >= 34 && withProjection ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 && withProjection -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> 0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(withProjection), type); true
        } catch (_: Exception) { false }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, notification(hasScreenAccess)) }
    }

    private fun notification(access: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bubble", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the Textrator bubble is on"
                setShowBadge(false)
            })
        }
        fun svc(action: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, ProjectionService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (access) "Textrator is ready" else "Textrator bubble is on")
            .setContentText(
                if (access) "Screen is read only when you tap the bubble."
                else "Tap the bubble to read the screen."
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (access) b.addAction(0, "Stop screen access", svc(ACTION_STOP_ACCESS, 3))
        b.addAction(0, "Close bubble", svc(ACTION_CLOSE, 2))
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun legacyData(intent: Intent?): Intent? = intent?.getParcelableExtra(EXTRA_DATA) as? Intent

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_SHOW = "com.getdown.textrator.SHOW"
        const val ACTION_GRANTED = "com.getdown.textrator.GRANTED"
        const val ACTION_CAPTURE = "com.getdown.textrator.CAPTURE"
        const val ACTION_STOP_ACCESS = "com.getdown.textrator.STOP_ACCESS"
        const val ACTION_CLOSE = "com.getdown.textrator.CLOSE"
        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: ProjectionService? = null
            private set

        fun close(ctx: Context) {
            ctx.startService(Intent(ctx, ProjectionService::class.java).setAction(ACTION_CLOSE))
        }
    }
}
