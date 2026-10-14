package com.buntap.app.ui.clicker

import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.domain.ClickerPlanner
import com.buntap.app.domain.Ids
import com.buntap.app.domain.model.ClickerConfig
import com.buntap.app.domain.model.GeometryCompatibility
import com.buntap.app.domain.model.GeometryRules
import com.buntap.app.domain.model.RepeatMode
import com.buntap.app.domain.model.TapPoint
import com.buntap.app.floating.PointPick
import com.buntap.app.ui.BunViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClickerViewModel(c: AppContainer) : BunViewModel(c) {

    val settings = c.settings.settings
    val geometry = c.displayMonitor.geometry
    val captureActive = c.capture.active

    /** In-memory source of truth for the screen; persisted shortly after each change. */
    private val _config = MutableStateFlow<ClickerConfig?>(null)
    val config: StateFlow<ClickerConfig?> = _config

    /** Paths of screenshots to open in the crop screen (POINT mode). */
    private val _cropRequests = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
    val cropRequests: SharedFlow<Pair<String, String>> = _cropRequests

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val loaded = c.clicker.get()
            _config.value = if (loaded.points.isEmpty() && loaded == ClickerConfig()) {
                // First use: start from the user's default timing.
                val s = c.settings.current
                loaded.copy(globalRate = s.defaultRate, tapsPerPoint = s.defaultTapCount, startDelayMs = s.startDelayMs)
            } else {
                loaded
            }
        }
        // Points can also be added from the floating panel; adopt saved changes unless this
        // screen has an edit of its own waiting to be saved.
        viewModelScope.launch {
            c.clicker.config.collect { saved ->
                val current = _config.value
                if (current != null && saveJob?.isActive != true && saved != current && saved != ClickerConfig()) {
                    _config.value = saved
                }
            }
        }
        viewModelScope.launch {
            c.overlays.pick.filterNotNull().collect { p ->
                if (p.requestKey.startsWith(KEY_PREFIX)) c.overlays.consumePick(p.requestKey)?.let(::applyPick)
            }
        }
    }

    fun update(transform: (ClickerConfig) -> ClickerConfig) {
        val current = _config.value ?: return
        val next = transform(current)
        _config.value = next
        saveJob?.cancel()
        // Saved on the app scope so leaving the screen never loses the last change.
        saveJob = c.appScope.launch {
            delay(250)
            c.clicker.save(next)
        }
    }

    private suspend fun flush() {
        saveJob?.cancel()
        _config.value?.let { c.clicker.save(it) }
    }

    fun compatibility(config: ClickerConfig): GeometryCompatibility =
        if (config.points.isEmpty()) GeometryCompatibility.Same else GeometryRules.compare(config.reference, geometry.value)

    // --- Points -------------------------------------------------------------------------

    fun newPointTemplate(): TapPoint {
        val g = geometry.value
        val n = (_config.value?.points?.size ?: 0) + 1
        return TapPoint(Ids.newId(), "Point $n", g.widthPx / 2, g.heightPx / 2)
    }

    /** Adds or replaces a point that was entered manually (current screen coordinates). */
    fun savePoint(point: TapPoint) {
        val g = geometry.value
        val cfg = _config.value ?: return
        if (!sameScreenAsOthers(cfg, point.id)) return
        update { config ->
            val exists = config.points.any { it.id == point.id }
            config.copy(
                points = if (exists) config.points.map { if (it.id == point.id) point else it } else config.points + point,
                reference = config.reference ?: g,
            )
        }
    }

    private fun sameScreenAsOthers(cfg: ClickerConfig, pointId: String?): Boolean {
        val others = cfg.points.filter { it.id != pointId }
        val ref = cfg.reference
        if (others.isNotEmpty() && ref != null && !ref.sameSize(geometry.value)) {
            show(
                "The screen is now ${geometry.value.describe()}, but the other points were picked on ${ref.describe()}. " +
                    "Scale or re-pick them first.",
            )
            return false
        }
        return true
    }

    private fun applyPick(pick: PointPick) {
        val cfg = _config.value ?: return
        val targetId = pick.requestKey.removePrefix(KEY_PREFIX).takeIf { it != NEW }
        val others = cfg.points.filter { it.id != targetId }
        val ref = cfg.reference
        if (others.isNotEmpty() && ref != null && !ref.sameSize(pick.geometry)) {
            show("The point was picked on a ${pick.geometry.describe()} screen, unlike the other points (${ref.describe()}). Not saved.")
            return
        }
        update { config ->
            val points = if (targetId == null) {
                config.points + TapPoint(Ids.newId(), "Point ${config.points.size + 1}", pick.x, pick.y)
            } else {
                config.points.map { if (it.id == targetId) it.copy(x = pick.x, y = pick.y) else it }
            }
            config.copy(points = points, reference = if (others.isEmpty()) pick.geometry else ref ?: pick.geometry)
        }
        show("Point saved at (${pick.x}, ${pick.y})")
    }

    fun deletePoint(id: String) = update { it.copy(points = it.points.filter { p -> p.id != id }) }

    fun movePoint(id: String, delta: Int) = update { config ->
        val i = config.points.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in config.points.indices) config
        else config.copy(points = config.points.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun setEnabled(id: String, enabled: Boolean) = update { config ->
        config.copy(points = config.points.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** Proportionally maps all points to the current screen (same orientation only). */
    fun scalePointsToCurrentScreen() = update { config ->
        val from = config.reference ?: return@update config
        val to = geometry.value
        config.copy(
            points = config.points.map { p ->
                val s = GeometryRules.scalePoint(p.x, p.y, from, to)
                p.copy(x = s.x, y = s.y)
            },
            reference = to,
        )
    }

    /** The user confirms the existing coordinates are correct for the current screen. */
    fun acceptCurrentScreen() = update { config ->
        val g = geometry.value
        config.copy(points = config.points.filter { g.contains(it.x, it.y) }, reference = g)
    }

    fun clearPoints() = update { it.copy(points = emptyList(), reference = null) }

    fun pointPickerKey(pointId: String?): String = KEY_PREFIX + (pointId ?: NEW)

    /** Takes a screenshot (capture must be running) for picking a point on it. */
    fun pickFromScreenshot(pointId: String?) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) { c.capture.snapshotBitmap() }
            if (bmp == null) {
                show("No screen frame available. Start screen capture first.")
                return@launch
            }
            val file = withContext(Dispatchers.IO) { c.images.saveTempScreenshot(bmp) }
            bmp.recycle()
            _cropRequests.tryEmit(file.path to pointPickerKey(pointId))
        }
    }

    // --- Running ------------------------------------------------------------------------

    /** True when the user must confirm an open-ended run first. */
    fun needsUnlimitedConfirmation(): Boolean {
        val cfg = _config.value ?: return false
        return cfg.repeatMode == RepeatMode.UNTIL_STOPPED && cfg.sessionTapLimit == null && cfg.maxDurationMs == null
    }

    fun start() {
        viewModelScope.launch {
            flush()
            c.launcher.armClicker() // the floating panel's Start reruns this
            startPrepared { c.launcher.prepareClicker() }
        }
    }

    fun arm() {
        viewModelScope.launch {
            flush()
            armForPanel("Auto clicker") { c.launcher.armClicker() }
        }
    }

    fun problems(config: ClickerConfig): List<String> =
        ClickerPlanner.validate(config, c.settings.current.rateLimits, geometry.value)

    companion object {
        private const val KEY_PREFIX = "clicker:"
        private const val NEW = "new"
    }
}
