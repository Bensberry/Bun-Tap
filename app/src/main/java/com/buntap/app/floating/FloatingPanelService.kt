package com.buntap.app.floating

import android.app.Service
import com.buntap.app.domain.model.SessionKind
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.gestures.detectTapGestures
import com.buntap.app.domain.model.PixelRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import android.view.Gravity
import android.os.Build
import android.graphics.PixelFormat
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buntap.app.BunTapApplication
import com.buntap.app.MainActivity
import com.buntap.app.automation.EngineState
import com.buntap.app.automation.StartResult
import com.buntap.app.domain.RateValidator
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.theme.BunTapTheme
import com.buntap.app.ui.theme.EmergencyRed
import com.buntap.app.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Compact, draggable control panel shown over other apps (requires the "Display over other
 * apps" permission). It only intercepts touches inside its own small window.
 */
class FloatingPanelService : Service() {

    private val container get() = (application as BunTapApplication).container
    private lateinit var windowManager: WindowManager
    private var view: ComposeView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val owner = OverlayLifecycleOwner()
    private val scope = MainScope()
    private var markers: TapMarkers? = null
    private val notice = mutableStateOf<String?>(null)
    private var regionView: ComposeView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!OverlayPermission.granted(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        owner.create()
        val density = resources.displayMetrics.density
        params = OverlayPermission.windowParams((12 * density).roundToInt(), (160 * density).roundToInt())
        val composeView = ComposeView(this).apply {
            owner.attachTo(this)
            setContent {
                BunTapTheme {
                    val state by container.engine.state.collectAsState()
                    val armed by container.engine.armed.collectAsState()
                    val settings by container.settings.settings.collectAsState()
                    val captureActive by container.capture.active.collectAsState()
                    PanelContent(
                        state = state,
                        armedName = armed?.name,
                        showStats = settings?.panelShowStats ?: true,
                        captureActive = captureActive,
                        notice = notice.value,
                        onPrimary = ::onPrimary,
                        onStop = { container.engine.stop() },
                        onEmergency = { container.engine.emergencyStop() },
                        onAddPoint = ::startAddPoint,
                        onAddTarget = ::startAddTarget,
                        onOpenApp = { openApp(null) },
                        onHide = ::hideAll,
                        onExit = ::exitPanel,
                        onDrag = ::moveBy,
                    )
                }
            }
        }
        composeView.viewTreeObserver.addOnGlobalLayoutListener { publishBounds() }
        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            // Permission revoked between the check and now.
            stopSelf()
            return
        }
        view = composeView
        owner.resume()
        _running.value = true
        // The panel is for controlling BunTap over other apps; BunTap's own screens have
        // full controls, so the panel steps aside while BunTap is in the foreground.
        val tapMarkers = TapMarkers(this, windowManager, owner, container, scope, ::showNotice)
        markers = tapMarkers
        scope.launch { container.clicker.config.collect { tapMarkers.sync(it) } }
        val hiddenFlow = combine(
            container.overlays.appInForeground,
            container.overlays.pickerActive,
            container.overlays.userHidden,
        ) { inApp, picking, userHidden -> inApp || picking || userHidden }
        scope.launch {
            hiddenFlow.collect { hidden ->
                composeView.visibility = if (hidden) View.GONE else View.VISIBLE
                if (hidden) container.overlays.setPanelBounds(null) else publishBounds()
            }
        }
        // Markers show the auto clicker's points (not while a visual or routine automation is armed).
        scope.launch {
            combine(hiddenFlow, container.engine.armed) { hidden, armed ->
                !hidden && (armed == null || armed.kind == SessionKind.CLICKER)
            }.collect { tapMarkers.setVisible(it) }
        }
        // While taps are being sent, markers let touches pass through to the app underneath.
        scope.launch {
            container.engine.state.collect { st ->
                tapMarkers.setTouchable(st.status != RunStatus.RUNNING && st.status != RunStatus.COUNTDOWN && st.status != RunStatus.STOPPING)
            }
        }
        // Points picked from the panel are added straight to the auto clicker.
        scope.launch {
            container.overlays.pick.collect { pick ->
                if (pick?.requestKey == PanelEditing.PICK_KEY) {
                    container.overlays.consumePick(PanelEditing.PICK_KEY)?.let { p ->
                        showNotice(container.panelEditing.addPoint(p.x, p.y, p.geometry))
                    }
                }
            }
        }
    }

    private fun onPrimary() {
        val status = container.engine.state.value.status
        when (status) {
            RunStatus.RUNNING -> container.engine.pause()
            RunStatus.PAUSED -> container.engine.resume()
            RunStatus.COUNTDOWN, RunStatus.STOPPING -> Unit
            else -> scope.launch {
                // Nothing chosen yet: the panel's Start runs the auto clicker.
                if (container.engine.armed.value == null) container.launcher.armClicker()
                val result = container.engine.startArmed()
                if (result is StartResult.Rejected) showNotice(result.reason)
            }
        }
    }

    private fun showNotice(text: String) {
        notice.value = text
        scope.launch {
            delay(4_000)
            if (notice.value == text) notice.value = null
        }
    }

    private fun moveBy(delta: Offset) {
        val v = view ?: return
        val screen = container.displayMonitor.current
        params.x = (params.x + delta.x.roundToInt()).coerceIn(0, (screen.widthPx - v.width).coerceAtLeast(0))
        params.y = (params.y + delta.y.roundToInt()).coerceIn(0, (screen.heightPx - v.height).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(v, params)
        } catch (_: IllegalArgumentException) {
        }
        publishBounds()
    }

    private fun publishBounds() {
        val v = view ?: return
        if (v.isAttachedToWindow && v.width > 0 && v.visibility == View.VISIBLE) {
            container.overlays.setPanelBounds(v.screenBounds())
        }
    }

    /** Pauses running automation so the user can safely edit. */
    private fun pauseIfRunning() {
        if (container.engine.state.value.status == RunStatus.RUNNING) {
            container.engine.pause()
            showNotice("Paused. Press play to resume")
        }
    }

    /** Drops a new numbered marker in the middle of the screen; the user drags it into place. */
    private fun startAddPoint() {
        pauseIfRunning()
        scope.launch {
            val g = container.displayMonitor.refresh()
            val message = container.panelEditing.addPoint(g.widthPx / 2, g.heightPx / 2, g)
            showNotice(if (message.startsWith("Added")) "${message.substringBefore(" at")} — drag its marker onto the spot" else message)
        }
    }

    /** Hides the panel and all markers; the notification brings them back. */
    private fun hideAll() {
        container.overlays.setUserHidden(true)
        if (!container.notifications.showPanelHidden()) {
            // Without notification permission the only way back is opening BunTap, which un-hides.
            container.notifications.showAlert("BunTap", "Controls hidden. Open BunTap to show them again.")
        }
    }

    /** Closes the floating panel and markers (they return the next time taps start). */
    private fun exitPanel() {
        stopSelf()
    }

    /** Lets the user draw a box on the live screen and saves it as a picture target. */
    private fun startAddTarget() {
        if (!container.capture.active.value) {
            showNotice("Screen capture is off. Opening BunTap to start it")
            openApp(null, requestCapture = true)
            return
        }
        if (regionView != null) return
        pauseIfRunning()
        val selection = mutableStateOf<Rect?>(null)
        val rv = ComposeView(this).apply {
            owner.attachTo(this)
            setContent {
                BunTapTheme {
                    RegionSelector(
                        selection = selection.value,
                        onSelection = { selection.value = it },
                        onCancel = { closeRegion() },
                        onSave = { saveRegion(selection.value) },
                    )
                }
            }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            windowManager.addView(rv, p)
        } catch (e: Exception) {
            showNotice("Could not show the selection overlay")
            return
        }
        regionView = rv
        container.overlays.setPickerActive(true)
    }

    private fun closeRegion() {
        regionView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        regionView = null
        container.overlays.setPickerActive(false)
    }

    private fun saveRegion(sel: Rect?) {
        val rv = regionView ?: return
        if (sel == null) return
        val origin = IntArray(2).also { rv.getLocationOnScreen(it) }
        val region = PixelRect(
            origin[0] + sel.left.roundToInt(),
            origin[1] + sel.top.roundToInt(),
            sel.width.roundToInt(),
            sel.height.roundToInt(),
        )
        // Remove the overlay (the panel stays hidden) and wait for a clean frame.
        regionView = null
        try {
            windowManager.removeView(rv)
        } catch (_: Exception) {
        }
        scope.launch {
            delay(400)
            val geometry = container.displayMonitor.refresh()
            val bitmap = withContext(Dispatchers.Default) { container.capture.snapshotBitmap() }
            container.overlays.setPickerActive(false)
            if (bitmap == null) {
                showNotice("No screen frame available. Is screen capture still on?")
                return@launch
            }
            val message = try {
                container.panelEditing.addTarget(bitmap, region, geometry)
            } finally {
                bitmap.recycle()
            }
            showNotice(message)
        }
    }

    private fun openApp(cropPath: String?, requestCapture: Boolean = false): Boolean = try {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply {
                    if (cropPath != null) putExtra(MainActivity.EXTRA_CROP_PATH, cropPath)
                    if (requestCapture) putExtra(MainActivity.EXTRA_REQUEST_CAPTURE, true)
                },
        )
        true
    } catch (e: Exception) {
        false
    }

    override fun onDestroy() {
        closeRegion()
        markers?.removeAll()
        markers = null
        container.overlays.setUserHidden(false)
        container.notifications.cancelPanelHidden()
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
        container.overlays.setPanelBounds(null)
        owner.destroy()
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            if (OverlayPermission.granted(context)) context.startService(Intent(context, FloatingPanelService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPanelService::class.java))
        }
    }
}

@Composable
private fun PanelContent(
    state: EngineState,
    armedName: String?,
    showStats: Boolean,
    captureActive: Boolean,
    notice: String?,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    onEmergency: () -> Unit,
    onAddPoint: () -> Unit,
    onAddTarget: () -> Unit,
    onOpenApp: () -> Unit,
    onHide: () -> Unit,
    onExit: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    val status = state.status
    val active = status.isActive
    val shape = RoundedCornerShape(18.dp)
    // Two compact rows so the panel fits narrow (360 dp) phones.
    Column(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .background(Color(0xF0151C25), shape)
            .border(1.dp, Color(0xFF2E3A4A), shape)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            onDrag(amount)
                        }
                    }
                    .semantics { contentDescription = "Drag to move the panel" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(BunIcons.DragHandle, contentDescription = null, tint = Color(0xFFA8B3C2))
            }
            Box(Modifier.size(10.dp).background(StatusColors.of(status), CircleShape))
            Column(
                Modifier
                    .padding(horizontal = 8.dp)
                    .widthIn(min = 96.dp, max = 124.dp)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onOpenApp() }) }
                    .semantics { contentDescription = "Open BunTap" },
            ) {
                Text(
                    text = state.sessionName?.takeIf { active } ?: armedName ?: "Auto clicker",
                    color = Color(0xFFE6EAF0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLine(state, showStats),
                    color = Color(0xFFA8B3C2),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PanelButton(BunIcons.VisibilityOff, "Hide controls and markers", onClick = onHide)
            PanelButton(Icons.Filled.Close, "Close floating controls", onClick = onExit)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelButton(
                icon = if (status == RunStatus.RUNNING) BunIcons.Pause else Icons.Filled.PlayArrow,
                label = when (status) {
                    RunStatus.RUNNING -> "Pause"
                    RunStatus.PAUSED -> "Resume"
                    else -> "Start"
                },
                enabled = status != RunStatus.COUNTDOWN && status != RunStatus.STOPPING,
                onClick = onPrimary,
            )
            PanelButton(BunIcons.Stop, "Stop", enabled = active, onClick = onStop)
            PanelButton(Icons.Filled.Add, "Add tap point", onClick = onAddPoint)
            PanelButton(BunIcons.Image, if (captureActive) "Add picture target" else "Add picture target (starts screen capture)", onClick = onAddTarget)
            EmergencyButton(onEmergency)
        }
        if (notice != null) {
            Text(
                notice,
                color = Color(0xFFFFC27A),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).widthIn(max = 260.dp),
            )
        }
    }
}

@Composable
private fun EmergencyButton(onEmergency: () -> Unit) {
    IconButton(
        onClick = onEmergency,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(40.dp)
            .background(EmergencyRed, CircleShape),
    ) {
        Icon(BunIcons.Bolt, contentDescription = "Emergency stop", tint = Color.White)
    }
}

@Composable
private fun PanelButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = if (enabled) Color(0xFFE6EAF0) else Color(0xFF5A6575))
    }
}

private fun statusLine(state: EngineState, showStats: Boolean): String {
    val s = state.stats
    val base = when (state.status) {
        RunStatus.COUNTDOWN -> "Starting in ${(state.countdownRemainingMs + 999) / 1000} s"
        RunStatus.RUNNING -> "Running"
        RunStatus.PAUSED -> "Paused"
        RunStatus.STOPPING -> "Stopping…"
        RunStatus.COMPLETED -> "Completed"
        RunStatus.STOPPED -> "Stopped"
        RunStatus.FAILED -> "Failed"
        RunStatus.IDLE -> "Ready · tap name to open"
    }
    if (!showStats || state.status == RunStatus.IDLE) return base
    val taps = s.plannedTaps?.let { "${s.completed}/$it" } ?: "${s.completed}"
    val tps = s.recentObservedTps?.let { " · ${RateValidator.formatTps(it)} TPS" } ?: ""
    return "$base · $taps taps$tps"
}

/** Full-screen overlay for drawing a box around a button on the live screen. */
@Composable
private fun RegionSelector(
    selection: Rect?,
    onSelection: (Rect) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    var anchor by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x33000000))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        anchor = it
                        onSelection(Rect(it, it))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val p = change.position
                        onSelection(Rect(minOf(anchor.x, p.x), minOf(anchor.y, p.y), maxOf(anchor.x, p.x), maxOf(anchor.y, p.y)))
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            selection?.let {
                drawRect(Color(0x33FFC27A), it.topLeft, it.size)
                drawRect(Color(0xFFFFC27A), it.topLeft, it.size, style = Stroke(width = 3.dp.toPx()))
            }
        }
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 12.dp, end = 12.dp)
                .background(Color(0xF0151C25), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF2E3A4A), RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            Text("Add a picture target", color = Color(0xFFE6EAF0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                selection?.let { "Box: ${it.width.roundToInt()} x ${it.height.roundToInt()} px. Drag again to redraw." }
                    ?: "Drag a box tightly around the button BunTap should look for.",
                color = Color(0xFFA8B3C2),
                fontSize = 12.sp,
            )
            Row(Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave, enabled = selection != null && selection.width >= 8f && selection.height >= 8f) {
                    Text("Save target")
                }
            }
        }
    }
}
