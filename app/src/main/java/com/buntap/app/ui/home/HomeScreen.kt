package com.buntap.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.capture.CaptureState
import com.buntap.app.floating.FloatingPanelService
import com.buntap.app.permissions.PermissionSnapshot
import com.buntap.app.routines.Routine
import com.buntap.app.ui.BunViewModel
import com.buntap.app.ui.MessageEffect
import com.buntap.app.ui.components.Banner
import com.buntap.app.ui.components.BannerTone
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.ConfirmDialog
import com.buntap.app.ui.components.CoreStats
import com.buntap.app.ui.components.Fmt
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.ScreenColumn
import com.buntap.app.ui.components.SectionCard
import com.buntap.app.ui.components.SessionControls
import com.buntap.app.ui.components.SessionHeader
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.navigation.LocalAppActions
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(c: AppContainer) : BunViewModel(c) {
    val armed = c.engine.armed
    val capture = c.capture.state
    val panelRunning = FloatingPanelService.running

    val recentRoutines: StateFlow<List<Routine>> = c.routines.routines
        .map { list -> list.sortedByDescending { it.lastRunAt ?: it.updatedAt }.take(4) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _permissions = MutableStateFlow<PermissionSnapshot?>(null)
    val permissions: StateFlow<PermissionSnapshot?> = _permissions

    fun refresh() {
        viewModelScope.launch(Dispatchers.Default) { _permissions.value = c.permissions.snapshot() }
    }

    fun runRoutine(routine: Routine) {
        viewModelScope.launch { if (routine.needsReview) c.routines.markReviewed(routine.id) }
        c.launcher.armRoutine(routine.id, routine.name)
        startPrepared(routine.id) { c.launcher.prepareRoutine(routine.id) }
    }

    fun startArmed() = viewModelScope.launch {
        val r = c.engine.startArmed()
        if (r is com.buntap.app.automation.StartResult.Rejected) show(r.reason)
    }

    fun togglePanel() {
        if (panelRunning.value) {
            FloatingPanelService.stop(c.appContext)
            viewModelScope.launch { c.settings.update { it.copy(floatingPanelEnabled = false) } }
        } else if (_permissions.value?.overlayGranted == true) {
            FloatingPanelService.start(c.appContext)
            viewModelScope.launch { c.settings.update { it.copy(floatingPanelEnabled = true) } }
        } else {
            show("Allow “Display over other apps” in Permissions first.")
        }
    }

    fun stopCapture() = c.capture.stop()
}

@Composable
fun HomeScreen(nav: Navigator) {
    val vm = bunViewModel { HomeViewModel(it) }
    val actions = LocalAppActions.current
    val state by vm.engineState.collectAsState()
    val armed by vm.armed.collectAsState()
    val capture by vm.capture.collectAsState()
    val panel by vm.panelRunning.collectAsState()
    val perms by vm.permissions.collectAsState()
    val recent by vm.recentRoutines.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var reviewRoutine by remember { mutableStateOf<Routine?>(null) }
    MessageEffect(vm, snackbar)
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            BunTopBar("BunTap") {
                IconButton(onClick = nav::permissions) { Icon(BunIcons.Shield, contentDescription = "Permissions and diagnostics") }
                IconButton(onClick = nav::settings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ScreenColumn(padding) {
            val p = perms
            if (p != null && !p.accessibilityConnected) {
                Banner(
                    if (p.accessibilityEnabledInSettings) "The accessibility service is enabled but not running yet. Open Permissions for help."
                    else "Turn on the BunTap accessibility service so BunTap can tap.",
                    BannerTone.WARNING,
                    "Fix",
                    nav::permissions,
                )
            }

            SectionCard {
                SessionHeader(state)
                if (state.status.isActive || state.stats.completed > 0) CoreStats(state.stats)
                SessionControls(state, vm::pause, vm::resume, vm::stop, vm::emergencyStop, vm::step)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = nav::monitor) { Text("Open monitor") }
                    if (!state.status.isActive && armed != null) TextButton(onClick = { vm.startArmed() }) { Text("Start “${armed?.name}”") }
                }
            }

            SectionCard(title = "Quick actions") {
                val items = listOf(
                    QuickAction("Auto clicker", "Tap points at a set rate", BunIcons.TouchApp, nav::clicker),
                    QuickAction("Visual targets", "Tap when an image appears", BunIcons.Image, nav::targets),
                    QuickAction("New routine", "Build a step-by-step automation", Icons.Filled.Add) { nav.routineEditor("new") },
                    QuickAction("Routine library", "Run, import and export", BunIcons.Routine, nav::routines),
                    QuickAction(
                        if (capture is CaptureState.Active) "Stop screen capture" else "Start screen capture",
                        when (val c = capture) {
                            is CaptureState.Active -> "Capturing ${c.geometry.describe()}"
                            is CaptureState.Ended -> c.reason
                            CaptureState.Starting -> "Starting…"
                            CaptureState.Inactive -> "Needed for visual targets"
                        },
                        BunIcons.Camera,
                    ) { if (capture is CaptureState.Active) vm.stopCapture() else actions.requestCapture() },
                    QuickAction(
                        if (panel) "Hide floating panel" else "Show floating panel",
                        armed?.let { "Armed: ${it.name}" } ?: "Controls over other apps",
                        BunIcons.FloatingPanel,
                        vm::togglePanel,
                    ),
                )
                items.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { QuickActionCard(it, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            SectionCard(
                title = "Recent routines",
                trailing = { TextButton(onClick = nav::routines) { Text("All") } },
            ) {
                if (recent.isEmpty()) HelpText("No routines yet. Create one, import one, or build the example routine from the library.")
                recent.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge)
                            HelpText(
                                r.lastRunAt?.let { "Last run ${Fmt.relative(it)} · ${r.lastRunStatus?.lowercase() ?: ""}" }
                                    ?: if (r.needsReview) "Imported — review before running" else "Never run",
                            )
                        }
                        IconButton(onClick = { nav.routineEditor(r.id) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${r.name}") }
                        IconButton(
                            onClick = { if (r.needsReview) reviewRoutine = r else vm.runRoutine(r) },
                            enabled = !state.status.isActive,
                        ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Run ${r.name}") }
                    }
                }
            }
            Text(
                "BunTap · by bensberry",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    reviewRoutine?.let { r ->
        ConfirmDialog(
            title = "Run imported routine?",
            text = "“${r.name}” was imported from a file. Open it in the editor to review its actions first. Run it now anyway?",
            confirmLabel = "I reviewed it — run",
            onConfirm = { vm.runRoutine(r) },
            onDismiss = { reviewRoutine = null },
        )
    }
}

private data class QuickAction(val title: String, val subtitle: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun QuickActionCard(action: QuickAction, modifier: Modifier) {
    OutlinedCard(modifier = modifier.heightIn(min = 96.dp).clickable(onClick = action.onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(action.title, style = MaterialTheme.typography.titleSmall)
            }
            Text(action.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}
