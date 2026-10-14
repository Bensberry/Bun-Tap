package com.buntap.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.vision.GrayFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State of the screen-capture session. */
sealed interface CaptureState {
    data object Inactive : CaptureState
    data object Starting : CaptureState
    data class Active(val geometry: ScreenGeometry, val sinceElapsedMs: Long) : CaptureState

    /** The previous session ended; [reason] explains why (shown to the user). */
    data class Ended(val reason: String) : CaptureState
}

/** Something that can supply the newest captured frame (the running capture service). */
interface FrameSource {
    val geometry: ScreenGeometry
    val frameCount: Long
    val lastFrameAtNs: Long
    fun grayFrame(scale: Double): GrayFrame?
    fun bitmap(): Bitmap?
}

/**
 * Owns the MediaProjection lifecycle from the app's point of view: builds the official consent
 * intent, starts/stops [ScreenCaptureService], and hands out frames.
 *
 * Consent cannot be restored silently: after the session ends (user stop, screen lock,
 * revocation, process death) the user must grant it again.
 */
class CaptureManager(private val context: Context) {

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Inactive)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    private var source: FrameSource? = null

    /** The system consent dialog. On Android 14+ it asks for the entire display. */
    fun consentIntent(): Intent {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
    }

    /** Called with the result of [consentIntent]. Starts the foreground capture service. */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            _state.value = CaptureState.Ended("Screen capture permission was declined")
            return
        }
        _state.value = CaptureState.Starting
        val intent = Intent(context, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_START)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop() {
        if (_state.value is CaptureState.Inactive || _state.value is CaptureState.Ended) return
        context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
    }

    internal fun onSessionStarted(frameSource: FrameSource) {
        source = frameSource
        _state.value = CaptureState.Active(frameSource.geometry, SystemClock.elapsedRealtime())
        _active.value = true
    }

    internal fun onGeometryChanged(geometry: ScreenGeometry) {
        val s = _state.value
        if (s is CaptureState.Active) _state.value = s.copy(geometry = geometry)
    }

    internal fun onSessionEnded(reason: String) {
        source = null
        _active.value = false
        _state.value = CaptureState.Ended(reason)
    }

    /** Newest frame in grayscale at [scale], or null when no session/frame is available. */
    fun acquireGrayFrame(scale: Double): GrayFrame? = source?.grayFrame(scale)

    /** Newest frame in color (for cropping and diagnostics). */
    fun snapshotBitmap(): Bitmap? = source?.bitmap()

    val frameCount: Long get() = source?.frameCount ?: 0L

    /** Milliseconds since the last frame arrived, or null. Static screens produce no new frames. */
    val lastFrameAgeMs: Long?
        get() = source?.lastFrameAtNs?.takeIf { it > 0 }?.let { (SystemClock.elapsedRealtimeNanos() - it) / 1_000_000L }
}
