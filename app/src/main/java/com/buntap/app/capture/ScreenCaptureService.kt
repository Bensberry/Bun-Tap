package com.buntap.app.capture

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Display
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.buntap.app.BunTapApplication
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.notifications.NotificationHelper
import com.buntap.app.vision.GrayFrame
import com.buntap.app.vision.OpenCvRuntime

/**
 * Foreground service (type `mediaProjection`) that owns the MediaProjection, a VirtualDisplay
 * mirroring the screen, and an ImageReader. Frames are processed on-device only.
 *
 * Android 14+ requires this foreground service to be running *before* the projection is
 * obtained, and each consent token can be used for only one session.
 */
class ScreenCaptureService : Service(), FrameSource {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val holder = LatestImageHolder()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    @Volatile
    override var geometry: ScreenGeometry = ScreenGeometry(1, 1)
        private set

    private val container get() = (application as BunTapApplication).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("BunTapCapture").apply { start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                if (projection != null) return START_NOT_STICKY
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (code != Activity.RESULT_OK || data == null) {
                    finish("Screen capture permission was not granted")
                } else {
                    startProjection(code, data)
                }
            }
            ACTION_STOP -> finish("Screen capture stopped")
            else -> {
                // Restarted without consent data (e.g. after process death): consent cannot be
                // restored silently, so stop.
                finish("Screen capture ended; start it again from BunTap")
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NotificationHelper.ID_CAPTURE, container.notifications.captureNotification(), type)
    }

    private fun startProjection(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
        if (p == null) {
            finish("Android refused to start the capture session")
            return
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { finish("Screen capture ended (stopped from the system, revoked, or the screen was locked)") }
            }
        }, handler)

        val g = container.displayMonitor.refresh()
        geometry = g
        val r = newReader(g)
        reader = r
        virtualDisplay = try {
            p.createVirtualDisplay(
                "BunTapCapture",
                g.widthPx,
                g.heightPx,
                g.densityDpi.coerceAtLeast(1),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface,
                null,
                handler,
            )
        } catch (e: Exception) {
            finish("Could not create the capture display: ${e.message}")
            return
        }
        registerDisplayListener()
        container.capture.onSessionStarted(this)
    }

    private fun newReader(g: ScreenGeometry): ImageReader =
        ImageReader.newInstance(g.widthPx, g.heightPx, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ holder.onImageAvailable(it) }, handler)
        }

    /** Keeps the capture size in sync with rotation and display-size changes. */
    private fun registerDisplayListener() {
        val dm = getSystemService(DisplayManager::class.java)
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) onDisplayChanged()
            }
        }
        dm.registerDisplayListener(l, handler)
        displayListener = l
    }

    private fun onDisplayChanged() {
        val vd = virtualDisplay ?: return
        val g = container.displayMonitor.refresh()
        if (g.widthPx == geometry.widthPx && g.heightPx == geometry.heightPx) {
            geometry = g
            return
        }
        val newReader = newReader(g)
        vd.resize(g.widthPx, g.heightPx, g.densityDpi.coerceAtLeast(1))
        vd.surface = newReader.surface
        holder.clear()
        reader?.close()
        reader = newReader
        geometry = g
        container.capture.onGeometryChanged(g)
    }

    // FrameSource ------------------------------------------------------------------------

    override val frameCount: Long get() = holder.frameCount
    override val lastFrameAtNs: Long get() = holder.lastFrameAtNs

    override fun grayFrame(scale: Double): GrayFrame? {
        if (!OpenCvRuntime.ensureLoaded()) return null
        val g = geometry
        return holder.withImage { image ->
            // A frame from before a rotation has the old size; never mix coordinate spaces.
            if (image.width != g.widthPx || image.height != g.heightPx) null
            else FrameConversion.toGrayFrame(image, scale, g)
        }
    }

    override fun bitmap(): Bitmap? = holder.withImage { FrameConversion.toBitmap(it) }

    // -------------------------------------------------------------------------------------

    private var finished = false

    private fun finish(reason: String) {
        if (finished) return
        finished = true
        displayListener?.let { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        displayListener = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        holder.clear()
        reader?.close()
        reader = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        container.capture.onSessionEnded(reason)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish("Screen capture service stopped")
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.buntap.app.capture.START"
        const val ACTION_STOP = "com.buntap.app.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
