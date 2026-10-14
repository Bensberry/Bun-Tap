package com.buntap.app.ui.targets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.domain.model.DetectionMode
import com.buntap.app.domain.model.RegionRect
import com.buntap.app.domain.model.TapAnchor
import com.buntap.app.domain.model.VisualTarget
import com.buntap.app.ui.BunViewModel
import com.buntap.app.ui.MessageEffect
import com.buntap.app.ui.components.Banner
import com.buntap.app.ui.components.BannerTone
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.ConfirmDialog
import com.buntap.app.ui.components.Fmt
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.IntField
import com.buntap.app.ui.components.MillisField
import com.buntap.app.ui.components.OptionalIntField
import com.buntap.app.ui.components.RateEditor
import com.buntap.app.ui.components.ScreenColumn
import com.buntap.app.ui.components.SecondsField
import com.buntap.app.ui.components.SectionCard
import com.buntap.app.ui.components.SegmentedChoice
import com.buntap.app.ui.components.SliderField
import com.buntap.app.ui.components.SwitchRow
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import com.buntap.app.vision.DetectionOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class DiagnosticUi(val running: Boolean, val summary: String? = null, val preview: ImageBitmap? = null, val found: Boolean = false)

class TargetEditorViewModel(c: AppContainer, private val targetId: String) : BunViewModel(c) {

    private val _target = MutableStateFlow<VisualTarget?>(null)
    val target: StateFlow<VisualTarget?> = _target
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing
    private val _diagnostic = MutableStateFlow<DiagnosticUi?>(null)
    val diagnostic: StateFlow<DiagnosticUi?> = _diagnostic

    val settings = c.settings.settings
    val captureActive = c.capture.active
    val geometry = c.displayMonitor.geometry

    private val _cropRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val cropRequests: SharedFlow<String> = _cropRequests
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closed: SharedFlow<Unit> = _closed

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val t = c.targets.get(targetId)
            if (t == null) {
                _missing.value = true
                return@launch
            }
            _target.value = t
            _image.value = withContext(Dispatchers.IO) { c.images.decode(t.imageFile)?.asImageBitmap() }
        }
    }

    /** Applies an edit immediately in the UI and saves it shortly afterwards. */
    fun update(transform: (VisualTarget) -> VisualTarget) {
        val current = _target.value ?: return
        val next = transform(current)
        _target.value = next
        saveJob?.cancel()
        saveJob = c.appScope.launch {
            delay(250)
            c.targets.save(next)
        }
    }

    private suspend fun flush() {
        saveJob?.cancel()
        _target.value?.let { c.targets.save(it) }
        // Reload so updatedAt (used by the template cache) is current.
        _target.value = c.targets.get(targetId) ?: _target.value
    }

    fun testDetection() {
        viewModelScope.launch {
            flush()
            val t = _target.value ?: return@launch
            _diagnostic.value = DiagnosticUi(running = true)
            val r = c.vision.diagnose(t)
            _diagnostic.value = DiagnosticUi(
                running = false,
                summary = r.summary,
                preview = r.preview?.asImageBitmap(),
                found = r.outcome is DetectionOutcome.Found,
            )
        }
    }

    fun pickRegionFromScreenshot() {
        viewModelScope.launch {
            flush()
            val bmp = withContext(Dispatchers.Default) { c.capture.snapshotBitmap() }
            if (bmp == null) {
                show("No screen frame available. Start screen capture first.")
                return@launch
            }
            val file = withContext(Dispatchers.IO) { c.images.saveTempScreenshot(bmp) }
            bmp.recycle()
            _cropRequests.tryEmit(file.path)
        }
    }

    /** Reloads after the region was set on the crop screen. */
    fun reload() {
        viewModelScope.launch {
            if (saveJob?.isActive == true) return@launch
            c.targets.get(targetId)?.let { _target.value = it }
        }
    }

    fun duplicate(onDone: (String) -> Unit) {
        viewModelScope.launch {
            flush()
            c.targets.duplicate(targetId)?.let { onDone(it.id) }
        }
    }

    fun delete() {
        viewModelScope.launch {
            saveJob?.cancel()
            c.targets.delete(targetId)
            c.vision.clearCache()
            _closed.tryEmit(Unit)
        }
    }
}

@Composable
fun TargetEditorScreen(nav: Navigator, targetId: String) {
    val vm = bunViewModel(key = "target:$targetId") { TargetEditorViewModel(it, targetId) }
    val target by vm.target.collectAsState()
    val image by vm.image.collectAsState()
    val missing by vm.missing.collectAsState()
    val settings by vm.settings.collectAsState()
    val captureActive by vm.captureActive.collectAsState()
    val geometry by vm.geometry.collectAsState()
    val diagnostic by vm.diagnostic.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    MessageEffect(vm, snackbar)
    LaunchedEffect(vm) { vm.cropRequests.collect { nav.crop(it, CropMode.REGION, targetId) } }
    LaunchedEffect(vm) { vm.closed.collect { nav.back() } }
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.reload()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            BunTopBar(target?.name ?: "Target", onBack = nav::back) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete target") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (missing) {
            ScreenColumn(padding) { Banner("This target no longer exists.", BannerTone.ERROR) }
            return@Scaffold
        }
        val t = target ?: return@Scaffold
        val s = settings ?: return@Scaffold
        val a = t.action
        val m = t.match
        ScreenColumn(padding) {
            SectionCard {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 180.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium).padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    image?.let { Image(it, contentDescription = "Target image", contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 164.dp)) }
                }
                OutlinedTextField(
                    value = t.name,
                    onValueChange = { n -> vm.update { it.copy(name = n.take(60)) } },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = t.name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("Enabled", t.enabled, { e -> vm.update { it.copy(enabled = e) } }, "Watched by visual automation. Routines can use it either way.")
                HelpText(
                    "${t.width}×${t.height} px · " +
                        (t.source?.let { "captured on ${it.describe()}" } ?: "imported image") +
                        (t.source?.takeIf { !it.sameSize(geometry) }?.let { " — the screen is now ${geometry.describe()}, so matching may fail" } ?: ""),
                )
            }

            SectionCard(title = "When detected") {
                DetectionMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = a.mode == mode, role = Role.RadioButton, onClick = {
                            vm.update { it.copy(action = it.action.copy(mode = mode)) }
                        }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = a.mode == mode, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(mode.label(), style = MaterialTheme.typography.bodyLarge)
                            HelpText(modeHelp(mode))
                        }
                    }
                }
            }

            if (a.mode != DetectionMode.NOTIFY_ONLY) {
                SectionCard(title = "Tapping") {
                    if (a.mode != DetectionMode.REPEAT_WHILE_VISIBLE) {
                        IntField(
                            "Taps per appearance",
                            a.tapsPerTrigger,
                            { v -> vm.update { it.copy(action = it.action.copy(tapsPerTrigger = v)) } },
                            1..1000,
                            supporting = "Performed as one batch; later scans never restart it.",
                        )
                    } else {
                        IntField(
                            "Maximum taps per appearance",
                            a.maxTapsPerAppearance,
                            { v -> vm.update { it.copy(action = it.action.copy(maxTapsPerAppearance = v)) } },
                            1..100_000,
                        )
                    }
                    RateEditor(a.rate, s.rateLimits, { r -> vm.update { it.copy(action = it.action.copy(rate = r)) } }, title = "Rate for this target")
                    OptionalIntField(
                        "Session limit for this target",
                        "Stop tapping this target after this many taps in one run.",
                        a.maxTotalTaps,
                        { v -> vm.update { it.copy(action = it.action.copy(maxTotalTaps = v)) } },
                        1..1_000_000,
                        defaultWhenEnabled = 100,
                        fieldLabel = "Maximum taps per session",
                    )
                    SecondsField(
                        "Delay after detection (before first tap)",
                        a.delayBeforeFirstTapMs,
                        { v -> vm.update { it.copy(action = it.action.copy(delayBeforeFirstTapMs = v)) } },
                        0L..60_000L,
                    )
                    SecondsField(
                        "Delay after last tap",
                        a.delayAfterLastTapMs,
                        { v -> vm.update { it.copy(action = it.action.copy(delayAfterLastTapMs = v)) } },
                        0L..60_000L,
                    )
                    SecondsField(
                        "Cooldown",
                        a.cooldownMs,
                        { v -> vm.update { it.copy(action = it.action.copy(cooldownMs = v)) } },
                        0L..600_000L,
                        supporting = "Minimum time after a batch before this target can trigger again.",
                    )
                }

                SectionCard(title = "Tap position") {
                    SegmentedChoice(
                        listOf(true to "Center", false to "Custom point"),
                        a.anchor.isCenter,
                        { center -> vm.update { it.copy(action = it.action.copy(anchor = if (center) TapAnchor.CENTER else TapAnchor(0.5f, 0.25f))) } },
                    )
                    if (!a.anchor.isCenter) {
                        SliderField("Horizontal position", a.anchor.fx, { v -> vm.update { it.copy(action = it.action.copy(anchor = it.action.anchor.copy(fx = v))) } }, 0f..1f, { Fmt.percent(it) })
                        SliderField("Vertical position", a.anchor.fy, { v -> vm.update { it.copy(action = it.action.copy(anchor = it.action.anchor.copy(fy = v))) } }, 0f..1f, { Fmt.percent(it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntField("Offset X", a.offsetX, { v -> vm.update { it.copy(action = it.action.copy(offsetX = v)) } }, -2000..2000, Modifier.weight(1f), suffix = "px")
                        IntField("Offset Y", a.offsetY, { v -> vm.update { it.copy(action = it.action.copy(offsetY = v)) } }, -2000..2000, Modifier.weight(1f), suffix = "px")
                    }
                }
            }

            SectionCard(title = "Matching") {
                SliderField(
                    "Confidence threshold",
                    m.threshold,
                    { v -> vm.update { it.copy(match = it.match.copy(threshold = (v * 100).roundToInt() / 100f)) } },
                    0.5f..0.99f,
                    { Fmt.score(it) },
                    supporting = "Higher = stricter. A high score is not a guarantee: similar-looking areas can also score high.",
                )
                MillisField("Scan interval", m.scanIntervalMs, { v -> vm.update { it.copy(match = it.match.copy(scanIntervalMs = v)) } }, 100L..10_000L)
                MillisField(
                    "Minimum visible time",
                    m.minVisibleMs,
                    { v -> vm.update { it.copy(match = it.match.copy(minVisibleMs = v)) } },
                    0L..60_000L,
                    supporting = "Ignore targets that flash on screen briefly.",
                )
                SwitchRow(
                    "Require disappearance",
                    m.requireDisappearance,
                    { v -> vm.update { it.copy(match = it.match.copy(requireDisappearance = v)) } },
                    "Only trigger again after the target has disappeared. When off, the cooldown re-arms it.",
                )
                SwitchRow(
                    "Allow several matches",
                    m.allowMultipleMatches,
                    { v -> vm.update { it.copy(match = it.match.copy(allowMultipleMatches = v)) } },
                    "Tap the best match even if similar ones exist. When off, ambiguous detections are skipped and logged.",
                )
                if (a.mode == DetectionMode.WAIT_AND_CLICK) {
                    SecondsField("Wait timeout", m.timeoutMs, { v -> vm.update { it.copy(match = it.match.copy(timeoutMs = v)) } }, 500L..3_600_000L)
                    IntField("Retries after timeout", m.retryCount, { v -> vm.update { it.copy(match = it.match.copy(retryCount = v)) } }, 0..20)
                }
            }

            SectionCard(title = "Search region") {
                val region = m.searchRegion
                SegmentedChoice(
                    listOf(true to "Whole screen", false to "Custom region"),
                    region == null,
                    { whole ->
                        vm.update {
                            it.copy(match = it.match.copy(searchRegion = if (whole) null else RegionRect(0, 0, geometry.widthPx, geometry.heightPx)))
                        }
                    },
                )
                if (region != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IntField("Left", region.left, { v -> vm.update { it.copy(match = it.match.copy(searchRegion = region.copy(left = v))) } }, 0..20_000, Modifier.weight(1f))
                        IntField("Top", region.top, { v -> vm.update { it.copy(match = it.match.copy(searchRegion = region.copy(top = v))) } }, 0..20_000, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IntField("Right", region.right, { v -> vm.update { it.copy(match = it.match.copy(searchRegion = region.copy(right = v))) } }, 1..20_000, Modifier.weight(1f))
                        IntField("Bottom", region.bottom, { v -> vm.update { it.copy(match = it.match.copy(searchRegion = region.copy(bottom = v))) } }, 1..20_000, Modifier.weight(1f))
                    }
                    if (!region.isValid() || region.width < t.width || region.height < t.height) {
                        Banner("The region must be valid and at least as large as the target image.", BannerTone.ERROR)
                    }
                }
                OutlinedButton(onClick = vm::pickRegionFromScreenshot, enabled = captureActive) { Text("Draw region on a screenshot") }
            }

            SectionCard(title = "Test detection", subtitle = "Searches the current screen once and shows what BunTap sees") {
                val d = diagnostic
                Button(onClick = vm::testDetection, enabled = captureActive && d?.running != true) {
                    if (d?.running == true) CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp) else Text("Test detection now")
                }
                if (!captureActive) HelpText("Start screen capture to test detection.")
                d?.summary?.let { Banner(it, if (d.found) BannerTone.SUCCESS else BannerTone.WARNING) }
                d?.preview?.let {
                    Image(it, contentDescription = "Detection preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp))
                    HelpText("Green: match · red cross: tap position · dashed blue: search region · orange: ambiguous candidates. Nothing is saved.")
                }
            }

            SectionCard(title = "Limitations") {
                HelpText(
                    "Template matching compares pixels. It can fail when the button changes size, rotation, theme, colour, language " +
                        "or animation, or when the screen resolution differs from the one it was captured on. When that happens, " +
                        "re-capture the target or lower the threshold carefully.",
                )
                OutlinedButton(onClick = { vm.duplicate { nav.targetEditor(it) } }) { Text("Duplicate target") }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Delete this target?",
            "The target and its image will be deleted. Routines that use it will no longer run.",
            "Delete",
            vm::delete,
            { confirmDelete = false },
            destructive = true,
        )
    }
}

private fun modeHelp(mode: DetectionMode): String = when (mode) {
    DetectionMode.ONCE_PER_APPEARANCE -> "Tap when it appears; tap again only after it disappears and reappears."
    DetectionMode.REPEAT_WHILE_VISIBLE -> "Keep tapping at the set rate while it stays visible (up to the per-appearance limit)."
    DetectionMode.WAIT_AND_CLICK -> "Wait for it (with timeout and retries), tap once, then stop watching it."
    DetectionMode.NOTIFY_ONLY -> "Record the detection in the log, never tap."
}
