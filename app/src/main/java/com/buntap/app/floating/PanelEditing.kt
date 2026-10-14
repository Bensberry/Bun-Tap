package com.buntap.app.floating

import android.graphics.Bitmap
import com.buntap.app.data.repository.ClickerRepository
import com.buntap.app.data.repository.TargetRepository
import com.buntap.app.domain.Ids
import com.buntap.app.domain.model.ClickerConfig
import com.buntap.app.domain.model.MatchSettings
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.SettingsMode
import com.buntap.app.domain.model.TapPoint
import com.buntap.app.domain.model.TapRate
import com.buntap.app.domain.model.TargetActionSettings
import com.buntap.app.settings.SettingsRepository
import com.buntap.app.ui.targets.CropViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Adds tap points and picture targets straight from the floating panel, without opening
 * BunTap. Uses the same rules as the in-app screens.
 */
class PanelEditing(
    private val clicker: ClickerRepository,
    private val targets: TargetRepository,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    /** Adds a clicker point picked on screen. Returns a short message for the panel. */
    suspend fun addPoint(x: Int, y: Int, geometry: ScreenGeometry): String = mutex.withLock {
        var config = clicker.get()
        if (config == ClickerConfig()) {
            // First point ever: start from the user's default timing, like the Clicker screen does.
            val s = settings.current
            config = config.copy(globalRate = s.defaultRate, tapsPerPoint = s.defaultTapCount, startDelayMs = s.startDelayMs)
        }
        val ref = config.reference
        if (config.points.isNotEmpty() && ref != null && !ref.sameSize(geometry)) {
            return "Not added: the other points were picked on a ${ref.describe()} screen"
        }
        val name = "Point ${config.points.size + 1}"
        clicker.save(
            config.copy(
                points = config.points + TapPoint(Ids.newId(), name, x, y),
                reference = if (config.points.isEmpty()) geometry else ref ?: geometry,
            ),
        )
        "Added $name at ($x, $y)"
    }

    /** Moves a point (marker dragged on screen). */
    suspend fun movePoint(id: String, x: Int, y: Int, geometry: ScreenGeometry): String? = mutex.withLock {
        val config = clicker.get()
        if (config.reference != null && !config.reference.sameSize(geometry)) {
            return "Not moved: the points belong to a ${config.reference.describe()} screen"
        }
        val nx = x.coerceIn(0, geometry.widthPx - 1)
        val ny = y.coerceIn(0, geometry.heightPx - 1)
        clicker.save(
            config.copy(
                points = config.points.map { if (it.id == id) it.copy(x = nx, y = ny) else it },
                reference = config.reference ?: geometry,
            ),
        )
        null
    }

    /**
     * Saves a point's own tap count, rate and enabled state. Giving one point its own values
     * switches the clicker to per-point settings; the other points keep using the global values.
     */
    suspend fun updatePoint(id: String, count: Int, rate: TapRate, enabled: Boolean) = mutex.withLock {
        val config = clicker.get()
        clicker.save(
            config.copy(
                settingsMode = SettingsMode.PER_POINT,
                points = config.points.map {
                    if (it.id == id) it.copy(countOverride = count, rateOverride = rate, enabled = enabled) else it
                },
            ),
        )
    }

    suspend fun deletePoint(id: String) = mutex.withLock {
        val config = clicker.get()
        val remaining = config.points.filter { it.id != id }
        clicker.save(config.copy(points = remaining, reference = if (remaining.isEmpty()) null else config.reference))
    }

    /**
     * Crops [region] (screen pixels) out of a full-screen [screenshot] and saves it as an
     * enabled visual target. Returns a short message for the panel.
     */
    suspend fun addTarget(screenshot: Bitmap, region: PixelRect, geometry: ScreenGeometry): String {
        if (screenshot.width != geometry.widthPx || screenshot.height != geometry.heightPx) {
            return "Not saved: the screen changed while selecting"
        }
        val l = region.left.coerceIn(0, screenshot.width - 1)
        val t = region.top.coerceIn(0, screenshot.height - 1)
        val w = region.width.coerceAtMost(screenshot.width - l)
        val h = region.height.coerceAtMost(screenshot.height - t)
        if (w < CropViewModel.MIN_SIDE || h < CropViewModel.MIN_SIDE) {
            return "Not saved: draw a box at least ${CropViewModel.MIN_SIDE}×${CropViewModel.MIN_SIDE} px"
        }
        val crop = withContext(Dispatchers.Default) { Bitmap.createBitmap(screenshot, l, t, w, h) }
        try {
            if (withContext(Dispatchers.Default) { CropViewModel.contrast(crop) } < CropViewModel.MIN_CONTRAST) {
                return "Not saved: that area has almost no detail"
            }
            val s = settings.current
            val name = "Target ${targets.count() + 1}"
            targets.create(
                name = name,
                bitmap = crop,
                source = geometry,
                match = MatchSettings(threshold = s.defaultThreshold, scanIntervalMs = s.defaultScanIntervalMs),
                action = TargetActionSettings(rate = s.defaultRate),
            )
            return "Saved “$name” (${w}×$h). Edit it in BunTap → Targets"
        } finally {
            crop.recycle()
        }
    }

    companion object {
        /** Pick requests from the panel use this key. */
        const val PICK_KEY = "panel:point"
    }
}
