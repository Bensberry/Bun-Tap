package com.buntap.app.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buntap.app.AppContainer
import com.buntap.app.domain.NumberInput
import com.buntap.app.domain.RateValidator
import com.buntap.app.domain.Validated
import com.buntap.app.domain.model.ClickerConfig
import com.buntap.app.domain.model.SettingsMode
import com.buntap.app.domain.model.TapPoint
import com.buntap.app.ui.theme.BunTapTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Numbered on-screen markers for the auto clicker's tap points (like classic auto-clicker apps).
 *
 * * Drag a marker to move its point; tap it to edit taps/speed, enable/disable or delete it.
 * * While taps are running the markers become pass-through (not touchable, 70 % opacity) so the
 *   automated taps reach the app underneath — Android only lets touches pass through another
 *   app's overlay when it is at most 80 % opaque.
 */
class TapMarkers(
    private val context: Context,
    private val windowManager: WindowManager,
    private val owner: OverlayLifecycleOwner,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val notice: (String) -> Unit,
) {
    private class Marker(
        val id: String,
        val view: ComposeView,
        val params: WindowManager.LayoutParams,
        val number: MutableState<Int>,
        val enabled: MutableState<Boolean>,
        var dragging: Boolean = false,
    )

    private val density = context.resources.displayMetrics.density
    private val sizePx = (MARKER_DP * density).roundToInt()
    private val markers = LinkedHashMap<String, Marker>()
    private var config: ClickerConfig = ClickerConfig()
    private var visible = false
    private var touchable = true

    /** Difference between window coordinates and real screen coordinates (learned once). */
    private var offsetX = 0
    private var offsetY = 0
    private var calibrated = false

    private var editor: ComposeView? = null

    fun sync(newConfig: ClickerConfig) {
        config = newConfig
        val ids = newConfig.points.map { it.id }.toSet()
        markers.keys.filter { it !in ids }.forEach { remove(it) }
        newConfig.points.forEachIndexed { index, p ->
            val m = markers[p.id] ?: create(p)
            m.number.value = index + 1
            m.enabled.value = p.enabled
            if (!m.dragging) place(m, p.x, p.y)
        }
    }

    fun setVisible(show: Boolean) {
        if (visible == show) return
        visible = show
        markers.values.forEach { it.view.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE }
        if (!show) closeEditor()
    }

    /** Pass-through while automation is tapping; interactive otherwise. */
    fun setTouchable(interactive: Boolean) {
        if (touchable == interactive) return
        touchable = interactive
        markers.values.forEach { applyTouchable(it) }
        if (!interactive) closeEditor()
    }

    fun removeAll() {
        closeEditor()
        markers.keys.toList().forEach { remove(it) }
    }

    private fun applyTouchable(m: Marker) {
        m.params.flags = if (touchable) BASE_FLAGS else BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        m.params.alpha = if (touchable) 1f else PASS_THROUGH_ALPHA
        update(m)
    }

    private fun create(p: TapPoint): Marker {
        val number = mutableStateOf(1)
        val enabled = mutableStateOf(p.enabled)
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val view = ComposeView(context)
        lateinit var marker: Marker
        view.apply {
            owner.attachTo(this)
            setContent {
                MarkerDot(
                    number = number.value,
                    enabled = enabled.value,
                    onTap = { openEditor(p.id) },
                    onDragStart = { marker.dragging = true },
                    onDrag = { dx, dy -> moveBy(marker, dx, dy) },
                    onDragEnd = { finishDrag(marker) },
                )
            }
            visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        }
        marker = Marker(p.id, view, params, number, enabled)
        if (!touchable) {
            params.flags = BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.alpha = PASS_THROUGH_ALPHA
        }
        params.x = p.x - sizePx / 2 - offsetX
        params.y = p.y - sizePx / 2 - offsetY
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
        }
        markers[p.id] = marker
        if (!calibrated) calibrateWith(marker, p.x, p.y)
        return marker
    }

    /** Learns how window coordinates map to screen coordinates on this device, then re-places markers. */
    private fun calibrateWith(m: Marker, wantX: Int, wantY: Int) {
        m.view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!m.view.isAttachedToWindow || m.view.width == 0) return
                m.view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (calibrated) return
                val c = center(m)
                offsetX += c.first - wantX
                offsetY += c.second - wantY
                calibrated = true
                sync(config)
            }
        })
    }

    private fun center(m: Marker): Pair<Int, Int> {
        val loc = IntArray(2)
        m.view.getLocationOnScreen(loc)
        return (loc[0] + m.view.width / 2) to (loc[1] + m.view.height / 2)
    }

    private fun place(m: Marker, x: Int, y: Int) {
        val nx = x - sizePx / 2 - offsetX
        val ny = y - sizePx / 2 - offsetY
        if (m.params.x == nx && m.params.y == ny) return
        m.params.x = nx
        m.params.y = ny
        update(m)
    }

    private fun moveBy(m: Marker, dx: Float, dy: Float) {
        m.params.x += dx.roundToInt()
        m.params.y += dy.roundToInt()
        update(m)
    }

    private fun finishDrag(m: Marker) {
        val (x, y) = center(m)
        scope.launch {
            container.panelEditing.movePoint(m.id, x, y, container.displayMonitor.refresh())?.let(notice)
            m.dragging = false
        }
    }

    private fun update(m: Marker) {
        try {
            if (m.view.isAttachedToWindow) windowManager.updateViewLayout(m.view, m.params)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun remove(id: String) {
        val m = markers.remove(id) ?: return
        try {
            windowManager.removeView(m.view)
        } catch (_: Exception) {
        }
    }

    // --- Editor card ---------------------------------------------------------------------

    private fun openEditor(id: String) {
        closeEditor()
        val index = config.points.indexOfFirst { it.id == id }
        if (index < 0) return
        val p = config.points[index]
        val perPoint = config.settingsMode == SettingsMode.PER_POINT
        val count = (if (perPoint) p.countOverride else null) ?: config.tapsPerPoint
        val rate = (if (perPoint) p.rateOverride else null) ?: config.globalRate
        val view = ComposeView(context).apply {
            owner.attachTo(this)
            setContent {
                BunTapTheme {
                    MarkerEditor(
                        title = "Point ${index + 1}",
                        position = "(${p.x}, ${p.y})",
                        initialCount = count,
                        initialTps = RateValidator.formatTps(rate.tps),
                        initialEnabled = p.enabled,
                        validateTps = { RateValidator.parseTps(it, container.settings.current.rateLimits) },
                        onSave = { c, r, e ->
                            closeEditor()
                            scope.launch { container.panelEditing.updatePoint(id, c, r, e) }
                        },
                        onDelete = {
                            closeEditor()
                            scope.launch { container.panelEditing.deletePoint(id) }
                        },
                        onCancel = { closeEditor() },
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE) so the keyboard works in the fields.
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.35f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        try {
            windowManager.addView(view, params)
            editor = view
        } catch (_: Exception) {
        }
    }

    private fun closeEditor() {
        editor?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        editor = null
    }

    private companion object {
        const val MARKER_DP = 44
        const val PASS_THROUGH_ALPHA = 0.7f
        const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
}

@Composable
private fun MarkerDot(
    number: Int,
    enabled: Boolean,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val fill = if (enabled) Color(0xCC2F5BD3) else Color(0x88505A68)
    Box(
        Modifier
            .size(44.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                ) { change, amount ->
                    change.consume()
                    onDrag(amount.x, amount.y)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .semantics { contentDescription = "Tap point $number. Drag to move, tap to edit." },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp).background(fill, CircleShape).border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        // Exact tap position.
        Box(Modifier.size(4.dp).background(Color(0xFFFFC27A), CircleShape))
    }
}

@Composable
private fun MarkerEditor(
    title: String,
    position: String,
    initialCount: Int,
    initialTps: String,
    initialEnabled: Boolean,
    validateTps: (String) -> Validated<com.buntap.app.domain.model.TapRate>,
    onSave: (Int, com.buntap.app.domain.model.TapRate, Boolean) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var countText by remember { mutableStateOf(initialCount.toString()) }
    var tpsText by remember { mutableStateOf(initialTps) }
    var enabled by remember { mutableStateOf(initialEnabled) }
    val count = NumberInput.int(countText, 1, 1_000_000, "Taps")
    val rate = validateTps(tpsText)
    Column(
        Modifier
            .widthIn(max = 320.dp)
            .background(Color(0xFF151C25), RoundedCornerShape(20.dp))
            .border(1.dp, Color(0xFF2E3A4A), RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("$title  ·  $position", color = Color(0xFFE6EAF0), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = countText,
            onValueChange = { countText = it },
            label = { Text("Taps each time") },
            isError = count is Validated.Invalid,
            supportingText = (count as? Validated.Invalid)?.let { { Text(it.message) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = tpsText,
            onValueChange = { tpsText = it },
            label = { Text("Speed (taps per second)") },
            isError = rate is Validated.Invalid,
            supportingText = {
                Text(
                    when (rate) {
                        is Validated.Invalid -> rate.message
                        is Validated.Valid -> "= one tap every ${RateValidator.formatMs(rate.value.intervalMs)} ms"
                    },
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled", color = Color(0xFFE6EAF0), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFFF6B6B)) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val c = (count as? Validated.Valid)?.value ?: return@Button
                    val r = (rate as? Validated.Valid)?.value ?: return@Button
                    onSave(c, r, enabled)
                },
                enabled = count is Validated.Valid && rate is Validated.Valid,
            ) { Text("Save") }
        }
    }
}
