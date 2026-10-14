package com.buntap.app.ui.routines

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buntap.app.domain.model.ActionRunStatus
import com.buntap.app.domain.model.GeometryCompatibility
import com.buntap.app.domain.model.GeometryRules
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.routines.RoutineDescriber
import com.buntap.app.routines.RoutineTree
import com.buntap.app.routines.RoutineValidator
import com.buntap.app.ui.MessageEffect
import com.buntap.app.ui.components.Banner
import com.buntap.app.ui.components.BannerTone
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.ConfirmDialog
import com.buntap.app.ui.components.CoreStats
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.IntField
import com.buntap.app.ui.components.OptionalIntField
import com.buntap.app.ui.components.OptionalSecondsField
import com.buntap.app.ui.components.RateEditor
import com.buntap.app.ui.components.SecondsField
import com.buntap.app.ui.components.SectionCard
import com.buntap.app.ui.components.SessionControls
import com.buntap.app.ui.components.SessionHeader
import com.buntap.app.ui.components.SwitchRow
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.navigation.LocalAppActions
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import com.buntap.app.ui.targets.CropMode
import com.buntap.app.ui.theme.StatusColors
import kotlinx.coroutines.launch

@Composable
fun RoutineEditorScreen(nav: Navigator, routineId: String) {
    val vm = bunViewModel(key = "routine-editor:$routineId") { RoutineEditorViewModel(it, routineId) }
    val actions = LocalAppActions.current
    val state by vm.state.collectAsState()
    val editing by vm.editing.collectAsState()
    val targets by vm.targets.collectAsState()
    val settings by vm.settings.collectAsState()
    val geometry by vm.geometry.collectAsState()
    val captureActive by vm.captureActive.collectAsState()
    val engine by vm.engineState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var addTo by remember { mutableStateOf<InsertAt?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var overlayHelp by remember { mutableStateOf(false) }
    var showDefaults by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    MessageEffect(vm, snackbar)
    LaunchedEffect(vm) { vm.cropRequests.collect { (path, key) -> nav.crop(path, CropMode.POINT, key) } }

    BackHandler(enabled = state.dirty) { confirmDiscard = true }
    val back = { if (state.dirty) confirmDiscard = true else nav.back() }

    val targetName: (String) -> String = { id -> targets.firstOrNull { it.id == id }?.name ?: "missing target" }
    val ownIds = remember(state.body.actions) { RoutineTree.all(state.body.actions).map { it.id }.toSet() }
    val runningThis = engine.kind == SessionKind.ROUTINE && engine.actionStatuses.keys.any { it in ownIds }
    val issuesById = state.issues.groupBy { it.actionId }

    Scaffold(
        topBar = {
            BunTopBar(if (state.routineId == null) "New routine" else "Edit routine", onBack = back) {
                TextButton(onClick = vm::saveAndReport, enabled = state.loaded) { Text(if (state.dirty) "Save" else "Saved") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = settings ?: return@Scaffold
        if (!state.loaded) return@Scaffold
        val rows = RoutineTree.flatten(state.body.actions)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    SectionCard {
                        OutlinedTextField(state.name, vm::setName, label = { Text("Routine name") }, singleLine = true, isError = state.name.isBlank(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(state.description, vm::setDescription, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
                        if (state.needsReview) Banner("Imported routine: review every action before running it.", BannerTone.WARNING)
                        val compat = GeometryRules.compare(state.body.reference, geometry)
                        if (RoutineValidator.usesFixedCoordinates(state.body) &&
                            (compat is GeometryCompatibility.Scalable || compat is GeometryCompatibility.Incompatible)
                        ) {
                            Banner(GeometryRules.explain(compat)!!, BannerTone.WARNING, "Use this screen", vm::useCurrentScreenAsReference)
                        }
                    }
                }
                item {
                    val d = state.body.defaults
                    SectionCard(
                        title = "Defaults and limits",
                        subtitle = if (showDefaults) "Actions use these unless they override them"
                        else "${com.buntap.app.domain.RateValidator.formatTps(d.rate.tps)} TPS · ${d.tapCount} tap(s) · " +
                            "target timeout ${com.buntap.app.ui.components.Fmt.millis(d.timeoutMs)}",
                        trailing = { TextButton(onClick = { showDefaults = !showDefaults }) { Text(if (showDefaults) "Hide" else "Edit") } },
                    ) {
                        if (!showDefaults) return@SectionCard
                        RateEditor(d.rate, s.rateLimits, { vm.setDefaults(d.copy(rate = it)) }, title = "Default rate")
                        IntField("Default tap count", d.tapCount, { vm.setDefaults(d.copy(tapCount = it)) }, 1..RoutineValidator.MAX_TAP_COUNT)
                        SecondsField("Default target timeout", d.timeoutMs, { vm.setDefaults(d.copy(timeoutMs = it)) }, 0L..RoutineValidator.MAX_TIMEOUT_MS, supporting = "How long target steps look for a target. 0 = single scan.")
                        OptionalSecondsField(
                            "Maximum run time", "Stop the routine after this much active time.", state.body.maxDurationMs, vm::setMaxDuration,
                            1_000L..86_400_000L, 300_000L, "Maximum run time",
                        )
                        OptionalIntField(
                            "Maximum taps", "Stop after this many successful taps.", state.body.maxTotalTaps, vm::setMaxTaps,
                            1..1_000_000, 500, "Maximum taps",
                        )
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Actions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Button(onClick = { addTo = InsertAt.End }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add action")
                        }
                    }
                }
                if (rows.isEmpty()) {
                    item { HelpText("No actions yet. Add a Wait, a Tap, or a visual-target step to begin.") }
                }
                items(rows, key = { row ->
                    when (row) {
                        is RoutineTree.Row.ActionRow -> "a:" + row.action.id
                        is RoutineTree.Row.SlotRow -> "s:" + row.parentId + row.slot
                    }
                }) { row ->
                    val indent = (row.depth * 18).dp
                    when (row) {
                        is RoutineTree.Row.SlotRow -> Row(Modifier.padding(start = indent), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.label + if (row.isEmpty) " (empty)" else "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = { addTo = InsertAt.Child(row.parentId, row.slot) }) { Text("+ Add here") }
                        }
                        is RoutineTree.Row.ActionRow -> ActionRowCard(
                            row = row,
                            modifier = Modifier.padding(start = indent),
                            summary = RoutineDescriber.summary(row.action, state.body.defaults, targetName),
                            status = if (runningThis) engine.actionStatuses[row.action.id] else null,
                            isCurrent = runningThis && engine.currentActionId == row.action.id,
                            problems = issuesById[row.action.id].orEmpty().map { it.message },
                            onClick = { vm.startEdit(row.action.id) },
                            onEnabled = { vm.toggleEnabled(row.action.id, it) },
                            onMove = { vm.move(row.action.id, it) },
                            onDuplicate = { vm.duplicate(row.action.id) },
                            onDelete = { confirmDelete = row.action.id },
                        )
                    }
                }
                item {
                    if (state.issues.isNotEmpty()) {
                        SectionCard(title = "Problems (${state.issues.size})", subtitle = "The routine can be saved, but not run, until these are fixed") {
                            state.issues.sortedBy { it.actionId != null }.take(8).forEach { Banner(it.message, BannerTone.ERROR) }
                        }
                    }
                }
                item {
                    SectionCard(title = "Run") {
                        if (runningThis || engine.kind == SessionKind.ROUTINE && engine.status.isActive) {
                            SessionHeader(engine)
                            CoreStats(engine.stats)
                            SwitchRow("Step by step", engine.stepMode, vm::setStepMode, "Pause before every action; press Step to continue.")
                        }
                        if (!engine.status.isActive) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.run(false) }, enabled = state.issues.isEmpty(), modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Run")
                                }
                                OutlinedButton(onClick = { vm.run(true) }, enabled = state.issues.isEmpty(), modifier = Modifier.weight(1f)) {
                                    Icon(BunIcons.SkipNext, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Step debug")
                                }
                            }
                            OutlinedButton(onClick = vm::arm, enabled = state.issues.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Arm floating panel") }
                            HelpText("Running saves the routine first. After a ${s.startDelayMs / 1000} s countdown it starts; switch to the target app.")
                        }
                        SessionControls(engine, vm::pause, vm::resume, vm::stop, vm::emergencyStop, vm::step)
                    }
                }
            }
        }
    }

    addTo?.let { at ->
        ActionTypePicker(
            onPick = { type ->
                addTo = null
                vm.startAdd(type, at)
            },
            onDismiss = { addTo = null },
            hasTargets = targets.isNotEmpty(),
        )
    }
    editing?.let { e ->
        val s = settings ?: return@let
        ActionEditorDialog(
            draft = e.draft,
            isNew = e.insertAt != null,
            targets = targets,
            defaults = state.body.defaults,
            limits = s.rateLimits,
            geometry = geometry,
            captureActive = captureActive,
            onChange = vm::updateDraft,
            onPickOnScreen = {
                if (!actions.startPointPicker(vm.pickKey(e.draft.id), "the tap position")) overlayHelp = true
            },
            onPickOnScreenshot = { vm.pickFromScreenshot(e.draft.id) },
            onSave = vm::commitEdit,
            onDismiss = vm::cancelEdit,
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save your changes before leaving?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    scope.launch {
                        vm.save()
                        nav.back()
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
                    TextButton(onClick = {
                        confirmDiscard = false
                        nav.back()
                    }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
    confirmDelete?.let { id ->
        val a = RoutineTree.find(state.body.actions, id)
        ConfirmDialog(
            "Delete action?",
            "“${a?.let { RoutineDescriber.title(it) } ?: "Action"}”" + if (a != null && RoutineTree.isGroup(a)) " and all steps inside it will be removed." else " will be removed.",
            "Delete",
            { vm.remove(id) },
            { confirmDelete = null },
            destructive = true,
        )
    }
    if (overlayHelp) {
        ConfirmDialog(
            "Allow “Display over other apps”",
            "The on-screen picker needs this permission. You can also type coordinates or pick them on a screenshot.",
            "Open Permissions",
            nav::permissions,
            { overlayHelp = false },
        )
    }
}

@Composable
private fun ActionRowCard(
    row: RoutineTree.Row.ActionRow,
    modifier: Modifier,
    summary: String,
    status: ActionRunStatus?,
    isCurrent: Boolean,
    problems: List<String>,
    onClick: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val a = row.action
    var menu by remember { mutableStateOf(false) }
    val borderColor = when {
        isCurrent -> MaterialTheme.colorScheme.primary
        problems.isNotEmpty() -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (isCurrent) 2.dp else 1.dp, borderColor),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(a), contentDescription = null, tint = if (a.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(RoutineDescriber.title(a), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    status?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            it.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (it) {
                                ActionRunStatus.SUCCEEDED -> StatusColors.running
                                ActionRunStatus.FAILED -> StatusColors.failed
                                ActionRunStatus.RUNNING -> StatusColors.countdown
                                else -> Color.Gray
                            },
                        )
                    }
                }
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (a.note.isNotBlank()) Text(a.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                problems.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
            }
            Switch(checked = a.enabled, onCheckedChange = onEnabled)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Action options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onClick() })
                    DropdownMenuItem(text = { Text("Move up") }, enabled = row.index > 0, onClick = { menu = false; onMove(-1) })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = row.index < row.siblingCount - 1, onClick = { menu = false; onMove(1) })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
