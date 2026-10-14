package com.buntap.app.ui.clicker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.buntap.app.domain.ClickerPlanner
import com.buntap.app.domain.RateValidator
import com.buntap.app.domain.model.GeometryCompatibility
import com.buntap.app.domain.model.GeometryRules
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.RepeatMode
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.domain.model.SettingsMode
import com.buntap.app.domain.model.TapPoint
import com.buntap.app.ui.MessageEffect
import com.buntap.app.ui.components.Banner
import com.buntap.app.ui.components.BannerTone
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.ConfirmDialog
import com.buntap.app.ui.components.CoreStats
import com.buntap.app.ui.components.EmptyState
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.IntField
import com.buntap.app.ui.components.OptionalIntField
import com.buntap.app.ui.components.OptionalSecondsField
import com.buntap.app.ui.components.RateEditor
import com.buntap.app.ui.components.ScreenColumn
import com.buntap.app.ui.components.SecondsField
import com.buntap.app.ui.components.SectionCard
import com.buntap.app.ui.components.SegmentedChoice
import com.buntap.app.ui.components.SessionControls
import com.buntap.app.ui.components.SessionHeader
import com.buntap.app.ui.components.SwitchRow
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.navigation.LocalAppActions
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import com.buntap.app.ui.targets.CropMode

@Composable
fun ClickerScreen(nav: Navigator) {
    val vm = bunViewModel { ClickerViewModel(it) }
    val actions = LocalAppActions.current
    val config by vm.config.collectAsState()
    val settings by vm.settings.collectAsState()
    val geometry by vm.geometry.collectAsState()
    val captureActive by vm.captureActive.collectAsState()
    val engine by vm.engineState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Pair<TapPoint, Boolean>?>(null) }
    var confirmUnlimited by remember { mutableStateOf(false) }
    var confirmAccept by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var overlayHelp by remember { mutableStateOf(false) }
    MessageEffect(vm, snackbar)
    LaunchedEffect(vm) { vm.cropRequests.collect { (path, key) -> nav.crop(path, CropMode.POINT, key) } }

    val pickOnScreen: (TapPoint?) -> Unit = { point ->
        val ok = actions.startPointPicker(vm.pointPickerKey(point?.id), point?.name ?: "a new tap point")
        if (!ok) overlayHelp = true
    }

    Scaffold(
        topBar = { BunTopBar("Auto clicker") { IconButton(onClick = nav::monitor) { Icon(BunIcons.Speed, contentDescription = "Monitor") } } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val cfg = config ?: return@Scaffold
        val s = settings ?: return@Scaffold
        val limits = s.rateLimits
        val plan = ClickerPlanner.plan(cfg)
        val running = engine.status.isActive && engine.kind == SessionKind.CLICKER
        ScreenColumn(padding) {
            when (val compat = vm.compatibility(cfg)) {
                is GeometryCompatibility.Scalable -> Banner(GeometryRules.explain(compat)!!, BannerTone.WARNING, "Scale", vm::scalePointsToCurrentScreen)
                is GeometryCompatibility.Incompatible -> Banner(GeometryRules.explain(compat)!!, BannerTone.ERROR, "Use anyway") { confirmAccept = true }
                else -> Unit
            }

            SectionCard(
                title = "Tap points",
                subtitle = "Tapped in this order. Screen: ${geometry.describe()}",
                trailing = { if (cfg.points.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") } },
            ) {
                if (cfg.points.isEmpty()) {
                    EmptyState("No tap points yet", "Pick positions on screen, enter coordinates, or pick them on a screenshot.", BunIcons.TouchApp)
                }
                cfg.points.forEachIndexed { index, point ->
                    PointRow(
                        index = index,
                        point = point,
                        mode = cfg.settingsMode,
                        isFirst = index == 0,
                        isLast = index == cfg.points.lastIndex,
                        onEnabled = { vm.setEnabled(point.id, it) },
                        onEdit = { editing = point to false },
                        onMove = { vm.movePoint(point.id, it) },
                    )
                    if (index < cfg.points.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Button(onClick = { pickOnScreen(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(BunIcons.Crosshair, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pick a point on screen")
                }
                OutlinedButton(onClick = { editing = vm.newPointTemplate() to true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Enter coordinates")
                }
                if (captureActive) {
                    OutlinedButton(onClick = { vm.pickFromScreenshot(null) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(BunIcons.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pick on a screenshot")
                    }
                }
            }

            SectionCard(title = "Rate and tap count") {
                SegmentedChoice(
                    listOf(SettingsMode.GLOBAL to "Same for all points", SettingsMode.PER_POINT to "Per-point settings"),
                    cfg.settingsMode,
                    { m -> vm.update { it.copy(settingsMode = m) } },
                )
                HelpText(
                    if (cfg.settingsMode == SettingsMode.GLOBAL) "Every point uses the rate and tap count below."
                    else "Points use their own rate/count where set (edit a point); otherwise the values below.",
                )
                RateEditor(
                    rate = cfg.globalRate,
                    limits = limits,
                    onRateChange = { r -> vm.update { it.copy(globalRate = r) } },
                    title = if (cfg.settingsMode == SettingsMode.GLOBAL) "Rate for all points" else "Default rate",
                    observedTps = if (running) engine.stats.recentObservedTps else null,
                )
                IntField(
                    "Taps per point",
                    cfg.tapsPerPoint,
                    { v -> vm.update { it.copy(tapsPerPoint = v) } },
                    1..ClickerPlanner.MAX_TAPS_PER_POINT,
                    supporting = "Applies to each point separately, every pass.",
                )
            }

            SectionCard(title = "Repeat and limits") {
                SegmentedChoice(
                    listOf(RepeatMode.ONE_PASS to "One pass", RepeatMode.FIXED_PASSES to "N passes", RepeatMode.UNTIL_STOPPED to "Until stopped"),
                    cfg.repeatMode,
                    { m -> vm.update { it.copy(repeatMode = m) } },
                )
                if (cfg.repeatMode == RepeatMode.FIXED_PASSES) {
                    IntField("Number of passes", cfg.passes, { v -> vm.update { it.copy(passes = v) } }, 1..ClickerPlanner.MAX_PASSES)
                }
                if (cfg.repeatMode == RepeatMode.UNTIL_STOPPED) {
                    Banner("Runs until you stop it or a limit is reached. You'll be asked to confirm before it starts.", BannerTone.WARNING)
                }
                OptionalIntField(
                    "Session tap limit",
                    "Stop after this many successful taps in total (all points).",
                    cfg.sessionTapLimit,
                    { v -> vm.update { it.copy(sessionTapLimit = v) } },
                    1..1_000_000,
                    defaultWhenEnabled = 100,
                    fieldLabel = "Total successful taps",
                )
                OptionalSecondsField(
                    "Maximum duration",
                    "Stop after this much active (unpaused) time.",
                    cfg.maxDurationMs,
                    { v -> vm.update { it.copy(maxDurationMs = v) } },
                    1_000L..86_400_000L,
                    defaultWhenEnabledMs = 60_000L,
                    fieldLabel = "Duration",
                )
                SecondsField(
                    "Start countdown",
                    cfg.startDelayMs,
                    { v -> vm.update { it.copy(startDelayMs = v) } },
                    0L..ClickerPlanner.MAX_START_DELAY_MS,
                    supporting = "Time to switch to the target app after pressing Start.",
                )
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            plan.plannedTaps?.let { "Planned: $it taps" } ?: "Planned: unlimited",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        HelpText(plan.explanation)
                        HelpText("Safety limits: ${s.maxTapsPerSession} taps, ${s.maxSessionMinutes} min, ${RateValidator.formatTps(s.maxTps)} TPS max.")
                    }
                }
            }

            SectionCard(title = "Run") {
                val problems = vm.problems(cfg)
                problems.take(3).forEach { Banner(it, BannerTone.ERROR) }
                if (running || engine.kind == SessionKind.CLICKER && engine.stats.completed > 0) {
                    SessionHeader(engine)
                    CoreStats(engine.stats)
                }
                if (!engine.status.isActive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (vm.needsUnlimitedConfirmation()) confirmUnlimited = true else vm.start() },
                            enabled = problems.isEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Start")
                        }
                        OutlinedButton(onClick = vm::arm, enabled = problems.isEmpty(), modifier = Modifier.weight(1f)) {
                            Text("Arm floating panel")
                        }
                    }
                    HelpText("After Start, switch to the target app during the ${cfg.startDelayMs / 1000} s countdown.")
                }
                SessionControls(engine, vm::pause, vm::resume, vm::stop, vm::emergencyStop)
            }
        }
    }

    editing?.let { (point, isNew) ->
        val s = settings ?: return@let
        val cfg = config ?: return@let
        PointEditorDialog(
            point = point,
            isNew = isNew,
            mode = cfg.settingsMode,
            globalRate = cfg.globalRate,
            globalCount = cfg.tapsPerPoint,
            limits = s.rateLimits,
            geometry = geometry,
            onSave = {
                vm.savePoint(it)
                editing = null
            },
            onDelete = {
                vm.deletePoint(point.id)
                editing = null
            },
            onRepickOnScreen = {
                editing = null
                pickOnScreen(point)
            },
            onRepickOnScreenshot = if (captureActive) ({ editing = null; vm.pickFromScreenshot(point.id) }) else null,
            onDismiss = { editing = null },
        )
    }
    if (confirmUnlimited) {
        ConfirmDialog(
            title = "Run until stopped?",
            text = "The auto clicker will keep tapping until you stop it (app, notification or floating panel) or a safety limit " +
                "is reached (${settings?.maxTapsPerSession} taps / ${settings?.maxSessionMinutes} min).",
            confirmLabel = "Start",
            onConfirm = vm::start,
            onDismiss = { confirmUnlimited = false },
        )
    }
    if (confirmAccept) {
        ConfirmDialog(
            title = "Use points on this screen?",
            text = "The points were picked on a differently oriented screen. BunTap will treat them as positions on the current " +
                "screen; points outside it are removed. Only continue if you are sure they are correct.",
            confirmLabel = "Use anyway",
            onConfirm = vm::acceptCurrentScreen,
            onDismiss = { confirmAccept = false },
            destructive = true,
        )
    }
    if (confirmClear) {
        ConfirmDialog("Remove all points?", "All tap points will be removed.", "Remove", vm::clearPoints, { confirmClear = false }, destructive = true)
    }
    if (overlayHelp) {
        ConfirmDialog(
            title = "Allow “Display over other apps”",
            text = "The on-screen picker shows a crosshair over other apps, which needs this permission. You can also enter " +
                "coordinates manually or pick them on a screenshot.",
            confirmLabel = "Open Permissions",
            onConfirm = nav::permissions,
            onDismiss = { overlayHelp = false },
        )
    }
}

@Composable
private fun PointRow(
    index: Int,
    point: TapPoint,
    mode: SettingsMode,
    isFirst: Boolean,
    isLast: Boolean,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(point.name, style = MaterialTheme.typography.bodyLarge)
            val extras = buildList {
                add("(${point.x}, ${point.y})")
                if (mode == SettingsMode.PER_POINT) {
                    point.countOverride?.let { add("$it taps") }
                    point.rateOverride?.let { add("${RateValidator.formatTps(it.tps)} TPS") }
                }
                if (point.repeat > 1) add("×${point.repeat} visits")
                if (point.preDelayMs > 0 || point.postDelayMs > 0) add("delays")
                point.timeoutMs?.let { add("timeout ${it / 1000.0}s") }
            }
            HelpText(extras.joinToString(" · "))
        }
        IconButton(onClick = { onMove(-1) }, enabled = !isFirst) { Icon(BunIcons.ArrowUp, contentDescription = "Move ${point.name} up") }
        IconButton(onClick = { onMove(1) }, enabled = !isLast) { Icon(BunIcons.ArrowDown, contentDescription = "Move ${point.name} down") }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${point.name}") }
        Switch(checked = point.enabled, onCheckedChange = onEnabled)
    }
}

@Composable
private fun PointEditorDialog(
    point: TapPoint,
    isNew: Boolean,
    mode: SettingsMode,
    globalRate: com.buntap.app.domain.model.TapRate,
    globalCount: Int,
    limits: RateLimits,
    geometry: ScreenGeometry,
    onSave: (TapPoint) -> Unit,
    onDelete: () -> Unit,
    onRepickOnScreen: () -> Unit,
    onRepickOnScreenshot: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var draft by remember(point.id) { mutableStateOf(point) }
    var confirmDelete by remember { mutableStateOf(false) }
    val coordinateError = com.buntap.app.domain.CoordinateValidator.check(draft.x, draft.y, geometry)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.94f)) {
            Column(Modifier.padding(20.dp)) {
                // Fields scroll; the action buttons below stay visible.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (isNew) "New tap point" else "Edit tap point", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(40)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("X", draft.x, { draft = draft.copy(x = it) }, 0 until geometry.widthPx, Modifier.weight(1f), suffix = "px")
                    IntField("Y", draft.y, { draft = draft.copy(y = it) }, 0 until geometry.heightPx, Modifier.weight(1f), suffix = "px")
                }
                coordinateError?.let { Banner(it, BannerTone.ERROR) }
                if (!isNew) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRepickOnScreen) { Text("Re-pick on screen") }
                        onRepickOnScreenshot?.let { OutlinedButton(onClick = it) { Text("On screenshot") } }
                    }
                }
                SwitchRow("Enabled", draft.enabled, { draft = draft.copy(enabled = it) })

                if (mode == SettingsMode.PER_POINT) {
                    SwitchRow(
                        "Own tapping rate",
                        draft.rateOverride != null,
                        { on -> draft = draft.copy(rateOverride = if (on) globalRate else null) },
                        "Otherwise ${RateValidator.formatTps(globalRate.tps)} TPS",
                    )
                    draft.rateOverride?.let { r ->
                        RateEditor(rate = r, limits = limits, onRateChange = { draft = draft.copy(rateOverride = it) }, title = "Rate for this point")
                    }
                    SwitchRow(
                        "Own tap count",
                        draft.countOverride != null,
                        { on -> draft = draft.copy(countOverride = if (on) globalCount else null) },
                        "Otherwise $globalCount taps",
                    )
                    draft.countOverride?.let { n ->
                        IntField("Taps per visit", n, { draft = draft.copy(countOverride = it) }, 1..ClickerPlanner.MAX_TAPS_PER_POINT)
                    }
                } else {
                    HelpText("Rate and tap count come from the global settings. Switch to per-point settings to override them.")
                }
                IntField("Visits per pass", draft.repeat, { draft = draft.copy(repeat = it) }, 1..ClickerPlanner.MAX_REPEAT, supporting = "Repeat this point's taps several times in each pass.")
                SecondsField("Delay before", draft.preDelayMs, { draft = draft.copy(preDelayMs = it) }, 0L..ClickerPlanner.MAX_DELAY_MS)
                SecondsField("Delay after", draft.postDelayMs, { draft = draft.copy(postDelayMs = it) }, 0L..ClickerPlanner.MAX_DELAY_MS)
                OptionalSecondsField(
                    "Point timeout",
                    "Move on to the next point if this one takes longer.",
                    draft.timeoutMs,
                    { draft = draft.copy(timeoutMs = it) },
                    ClickerPlanner.MIN_TIMEOUT_MS..ClickerPlanner.MAX_TIMEOUT_MS,
                    defaultWhenEnabledMs = 10_000L,
                    fieldLabel = "Timeout",
                )
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete point", tint = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onSave(draft) }, enabled = coordinateError == null && draft.name.isNotBlank()) { Text("Save") }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete “${draft.name}”?", "This tap point will be removed.", "Delete", onDelete, { confirmDelete = false }, destructive = true)
    }
}
