package com.buntap.app.ui.targets

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.domain.model.MatchSettings
import com.buntap.app.domain.model.PixelPoint
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.RegionRect
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TargetActionSettings
import com.buntap.app.floating.PointPick
import com.buntap.app.ui.BunViewModel
import com.buntap.app.ui.MessageEffect
import com.buntap.app.ui.components.Banner
import com.buntap.app.ui.components.BannerTone
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.IntField
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class CropViewModel(
    c: AppContainer,
    private val path: String,
    val mode: CropMode,
    private val requestKey: String,
) : BunViewModel(c) {

    private var bitmap: Bitmap? = null
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    /** Selection in image pixels. */
    private val _selection = MutableStateFlow<PixelRect?>(null)
    val selection: StateFlow<PixelRect?> = _selection

    private val _point = MutableStateFlow<PixelPoint?>(null)
    val point: StateFlow<PixelPoint?> = _point

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    /** The screen this screenshot matches, or null (imported image / screen changed). */
    var screen: ScreenGeometry? = null
        private set

    private val _done = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    /** Emits the new target id (TARGET mode) or null when finished. */
    val done: SharedFlow<String?> = _done

    init {
        c.notifications.cancelCaptureReady()
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { c.images.loadTemp(path) }
            if (bmp == null) {
                _loadError.value = "This screenshot is no longer available."
                return@launch
            }
            bitmap = bmp
            _image.value = bmp.asImageBitmap()
            val g = c.displayMonitor.refresh()
            screen = if (bmp.width == g.widthPx && bmp.height == g.heightPx) g else null
            val n = c.targets.count() + 1
            _name.value = "Target $n"
            if (mode != CropMode.POINT) {
                // Start with a centered selection the user can move or redraw.
                val w = (bmp.width / 4).coerceAtLeast(8)
                val h = (bmp.height / 10).coerceAtLeast(8)
                _selection.value = PixelRect((bmp.width - w) / 2, (bmp.height - h) / 2, w, h)
            } else {
                _point.value = PixelPoint(bmp.width / 2, bmp.height / 2)
            }
        }
    }

    fun setSelection(rect: PixelRect) {
        val bmp = bitmap ?: return
        val l = rect.left.coerceIn(0, bmp.width - 1)
        val t = rect.top.coerceIn(0, bmp.height - 1)
        val w = rect.width.coerceIn(1, bmp.width - l)
        val h = rect.height.coerceIn(1, bmp.height - t)
        _selection.value = PixelRect(l, t, w, h)
    }

    fun setPoint(p: PixelPoint) {
        val bmp = bitmap ?: return
        _point.value = PixelPoint(p.x.coerceIn(0, bmp.width - 1), p.y.coerceIn(0, bmp.height - 1))
    }

    fun setName(n: String) {
        _name.value = n.take(60)
    }

    fun useWholeImage() {
        val bmp = bitmap ?: return
        _selection.value = PixelRect(0, 0, bmp.width, bmp.height)
    }

    fun save() {
        when (mode) {
            CropMode.TARGET -> saveTarget()
            CropMode.REGION -> saveRegion()
            CropMode.POINT -> savePoint()
        }
    }

    private fun saveTarget() {
        val bmp = bitmap ?: return
        val sel = _selection.value ?: return show("Select an area first.")
        if (sel.width < MIN_SIDE || sel.height < MIN_SIDE) return show("The selection must be at least $MIN_SIDE×$MIN_SIDE pixels.")
        if (_name.value.isBlank()) return show("Give the target a name.")
        viewModelScope.launch {
            val crop = withContext(Dispatchers.Default) { Bitmap.createBitmap(bmp, sel.left, sel.top, sel.width, sel.height) }
            if (withContext(Dispatchers.Default) { contrast(crop) } < MIN_CONTRAST) {
                show("This area has almost no detail, so it would match almost anything. Select a more distinctive area.")
                return@launch
            }
            val s = c.settings.current
            val target = c.targets.create(
                name = _name.value,
                bitmap = crop,
                source = screen,
                match = MatchSettings(threshold = s.defaultThreshold, scanIntervalMs = s.defaultScanIntervalMs),
                action = TargetActionSettings(rate = s.defaultRate),
            )
            crop.recycle()
            _done.tryEmit(target.id)
        }
    }

    private fun saveRegion() {
        val sel = _selection.value ?: return show("Select an area first.")
        if (screen == null) return show("The screenshot does not match the current screen size, so it cannot define a region.")
        viewModelScope.launch {
            val t = c.targets.get(requestKey) ?: return@launch show("The target no longer exists.")
            if (sel.width < t.width || sel.height < t.height) {
                show("The region must be at least as large as the target image (${t.width}×${t.height}).")
                return@launch
            }
            c.targets.save(t.copy(match = t.match.copy(searchRegion = RegionRect(sel.left, sel.top, sel.right, sel.bottom))))
            _done.tryEmit(null)
        }
    }

    private fun savePoint() {
        val p = _point.value ?: return show("Tap the screenshot to choose a point.")
        val g = screen ?: return show("The screenshot does not match the current screen size; take a new one.")
        c.overlays.publishPick(PointPick(requestKey, p.x, p.y, g))
        _done.tryEmit(null)
    }

    override fun onCleared() {
        // Privacy: the full screenshot is never kept after this screen closes.
        c.images.deleteTemp(path)
        super.onCleared()
    }

    companion object {
        const val MIN_SIDE = 8
        const val MIN_CONTRAST = 4.0

        /** Standard deviation of luminance (0–255), sampled. */
        fun contrast(bmp: Bitmap): Double {
            val step = maxOf(1, sqrt((bmp.width * bmp.height) / 40_000.0).toInt())
            var n = 0
            var sum = 0.0
            var sumSq = 0.0
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    val p = bmp.getPixel(x, y)
                    val l = 0.299 * AndroidColor.red(p) + 0.587 * AndroidColor.green(p) + 0.114 * AndroidColor.blue(p)
                    sum += l
                    sumSq += l * l
                    n++
                    x += step
                }
                y += step
            }
            if (n == 0) return 0.0
            val mean = sum / n
            return sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        }
    }
}

private enum class DragKind { NONE, NEW, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Mutable gesture state shared by the drag callbacks (not UI state). */
private class DragState {
    var kind = DragKind.NONE
    var anchor = Offset.Zero
}

@Composable
fun CropScreen(nav: Navigator, path: String, mode: CropMode, requestKey: String) {
    val vm = bunViewModel(key = "crop:$path:$mode:$requestKey") { CropViewModel(it, path, mode, requestKey) }
    val image by vm.image.collectAsState()
    val loadError by vm.loadError.collectAsState()
    val selection by vm.selection.collectAsState()
    val point by vm.point.collectAsState()
    val name by vm.name.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var fineTune by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    MessageEffect(vm, snackbar)
    LaunchedEffect(vm) {
        vm.done.collect { id -> if (id != null) nav.replace(com.buntap.app.ui.navigation.TargetEditorRoute(id)) else nav.back() }
    }
    val title = when (mode) {
        CropMode.TARGET -> "Crop a visual target"
        CropMode.REGION -> "Choose search region"
        CropMode.POINT -> "Choose a tap point"
    }

    Scaffold(topBar = { BunTopBar(title, onBack = nav::back) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            loadError?.let { Banner(it, BannerTone.ERROR) }
            val img = image
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
                if (img == null) return@BoxWithConstraints
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val scale = min(boxW / img.width, boxH / img.height)
                val offX = (boxW - img.width * scale) / 2f
                val offY = (boxH - img.height * scale) / 2f
                val handleRadius = with(LocalDensity.current) { 22.dp.toPx() }
                fun toImage(o: Offset) = Offset((o.x - offX) / scale, (o.y - offY) / scale)

                Image(img, contentDescription = "Screenshot", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                val drag = remember { DragState() }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(mode, scale, offX, offY) {
                            if (mode == CropMode.POINT) {
                                detectTapGestures { pos -> toImage(pos).let { vm.setPoint(PixelPoint(it.x.roundToInt(), it.y.roundToInt())) } }
                            }
                        }
                        .pointerInput(mode, scale, offX, offY) {
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val ip = toImage(pos)
                                    if (mode == CropMode.POINT) {
                                        drag.kind = DragKind.MOVE
                                        vm.setPoint(PixelPoint(ip.x.roundToInt(), ip.y.roundToInt()))
                                        return@detectDragGestures
                                    }
                                    val sel = vm.selection.value
                                    val r = handleRadius / scale
                                    fun near(x: Int, y: Int) = abs(ip.x - x) < r && abs(ip.y - y) < r
                                    drag.kind = when {
                                        sel != null && near(sel.left, sel.top) -> DragKind.TOP_LEFT
                                        sel != null && near(sel.right, sel.top) -> DragKind.TOP_RIGHT
                                        sel != null && near(sel.left, sel.bottom) -> DragKind.BOTTOM_LEFT
                                        sel != null && near(sel.right, sel.bottom) -> DragKind.BOTTOM_RIGHT
                                        sel != null && sel.contains(ip.x.roundToInt(), ip.y.roundToInt()) -> DragKind.MOVE
                                        else -> DragKind.NEW
                                    }
                                    drag.anchor = ip
                                },
                                onDragEnd = { drag.kind = DragKind.NONE },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val ip = toImage(change.position)
                                    if (mode == CropMode.POINT) {
                                        vm.setPoint(PixelPoint(ip.x.roundToInt(), ip.y.roundToInt()))
                                        return@detectDragGestures
                                    }
                                    val sel = vm.selection.value
                                    val dx = (amount.x / scale).roundToInt()
                                    val dy = (amount.y / scale).roundToInt()
                                    when (drag.kind) {
                                        DragKind.NEW -> {
                                            val a = drag.anchor
                                            val l = min(a.x, ip.x).roundToInt()
                                            val t = min(a.y, ip.y).roundToInt()
                                            vm.setSelection(PixelRect(l, t, abs(ip.x - a.x).roundToInt().coerceAtLeast(1), abs(ip.y - a.y).roundToInt().coerceAtLeast(1)))
                                        }
                                        DragKind.MOVE -> if (sel != null) {
                                            val img2 = image ?: return@detectDragGestures
                                            val l = (sel.left + dx).coerceIn(0, img2.width - sel.width)
                                            val t = (sel.top + dy).coerceIn(0, img2.height - sel.height)
                                            vm.setSelection(PixelRect(l, t, sel.width, sel.height))
                                        }
                                        DragKind.TOP_LEFT -> if (sel != null) vm.setSelection(fromCorners(ip.x, ip.y, sel.right.toFloat(), sel.bottom.toFloat()))
                                        DragKind.TOP_RIGHT -> if (sel != null) vm.setSelection(fromCorners(sel.left.toFloat(), ip.y, ip.x, sel.bottom.toFloat()))
                                        DragKind.BOTTOM_LEFT -> if (sel != null) vm.setSelection(fromCorners(ip.x, sel.top.toFloat(), sel.right.toFloat(), ip.y))
                                        DragKind.BOTTOM_RIGHT -> if (sel != null) vm.setSelection(fromCorners(sel.left.toFloat(), sel.top.toFloat(), ip.x, ip.y))
                                        DragKind.NONE -> Unit
                                    }
                                },
                            )
                        },
                ) {
                    val accent = Color(0xFFFFC27A)
                    val sel = selection
                    if (sel != null && mode != CropMode.POINT) {
                        val l = offX + sel.left * scale
                        val t = offY + sel.top * scale
                        val w = sel.width * scale
                        val h = sel.height * scale
                        val dim = Color.Black.copy(alpha = 0.5f)
                        // Dim everything outside the selection.
                        drawRect(dim, Offset(offX, offY), Size(img.width * scale, t - offY))
                        drawRect(dim, Offset(offX, t + h), Size(img.width * scale, offY + img.height * scale - t - h))
                        drawRect(dim, Offset(offX, t), Size(l - offX, h))
                        drawRect(dim, Offset(l + w, t), Size(offX + img.width * scale - l - w, h))
                        drawRect(accent, Offset(l, t), Size(w, h), style = Stroke(width = 2.dp.toPx()))
                        listOf(Offset(l, t), Offset(l + w, t), Offset(l, t + h), Offset(l + w, t + h)).forEach {
                            drawCircle(accent, radius = 7.dp.toPx(), center = it)
                        }
                    }
                    val p = point
                    if (p != null && mode == CropMode.POINT) {
                        val cx = offX + p.x * scale
                        val cy = offY + p.y * scale
                        val arm = 18.dp.toPx()
                        drawLine(accent, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = 2.dp.toPx())
                        drawLine(accent, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = 2.dp.toPx())
                        drawCircle(accent, radius = arm * 0.6f, center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (mode) {
                        CropMode.TARGET -> HelpText("Drag to select the button or icon. Drag inside to move, drag corners to resize. Keep it tight and distinctive.")
                        CropMode.REGION -> HelpText("Select the part of the screen where the target can appear. Smaller regions are faster and avoid false matches.")
                        CropMode.POINT -> HelpText("Tap or drag to place the crosshair on the spot BunTap should tap.")
                    }
                    if (mode == CropMode.TARGET) {
                        OutlinedTextField(name, vm::setName, label = { Text("Target name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { fineTune = !fineTune }) { Text(if (fineTune) "Hide exact values" else "Exact values") }
                        if (mode == CropMode.TARGET) TextButton(onClick = vm::useWholeImage) { Text("Use whole image") }
                    }
                    val sel = selection
                    if (fineTune && sel != null && mode != CropMode.POINT) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IntField("Left", sel.left, { vm.setSelection(sel.copy(left = it)) }, 0..100_000, Modifier.weight(1f))
                            IntField("Top", sel.top, { vm.setSelection(sel.copy(top = it)) }, 0..100_000, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IntField("Width", sel.width, { vm.setSelection(sel.copy(width = it)) }, 1..100_000, Modifier.weight(1f))
                            IntField("Height", sel.height, { vm.setSelection(sel.copy(height = it)) }, 1..100_000, Modifier.weight(1f))
                        }
                    } else if (sel != null && mode != CropMode.POINT) {
                        HelpText("Selection: ${sel.width}×${sel.height} px at (${sel.left}, ${sel.top})")
                    }
                    val p = point
                    if (p != null && mode == CropMode.POINT) {
                        if (fineTune) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                IntField("X", p.x, { vm.setPoint(p.copy(x = it)) }, 0..100_000, Modifier.weight(1f))
                                IntField("Y", p.y, { vm.setPoint(p.copy(y = it)) }, 0..100_000, Modifier.weight(1f))
                            }
                        } else {
                            HelpText("Point: (${p.x}, ${p.y})")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = nav::back, modifier = Modifier.weight(1f)) { Text("Cancel") }
                        Button(onClick = vm::save, enabled = image != null, modifier = Modifier.weight(1f)) {
                            Text(
                                when (mode) {
                                    CropMode.TARGET -> "Save target"
                                    CropMode.REGION -> "Use region"
                                    CropMode.POINT -> "Use point"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fromCorners(x1: Float, y1: Float, x2: Float, y2: Float): PixelRect {
    val l = min(x1, x2).roundToInt()
    val t = min(y1, y2).roundToInt()
    return PixelRect(l, t, abs(x2 - x1).roundToInt().coerceAtLeast(1), abs(y2 - y1).roundToInt().coerceAtLeast(1))
}
