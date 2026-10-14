package com.buntap.app.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.buntap.app.domain.model.ScreenGeometry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the real size and rotation of the default display. Tap coordinates, screen captures
 * and the floating panel all use this coordinate space.
 */
class DisplayMonitor(context: Context) {

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val _geometry = MutableStateFlow(read())
    val geometry: StateFlow<ScreenGeometry> = _geometry.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refresh()
        }
    }

    init {
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    val current: ScreenGeometry get() = _geometry.value

    /** Re-reads the display and publishes the result. */
    fun refresh(): ScreenGeometry {
        val g = read()
        _geometry.value = g
        return g
    }

    @Suppress("DEPRECATION") // getRealMetrics still reports the full physical display size.
    private fun read(): ScreenGeometry {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return ScreenGeometry(1080, 1920)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return ScreenGeometry(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            rotation = display.rotation,
            densityDpi = metrics.densityDpi,
        )
    }
}
