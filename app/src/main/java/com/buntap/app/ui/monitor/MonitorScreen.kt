package com.buntap.app.ui.monitor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.data.db.SessionEntity
import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.ExecutionEvent
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.StatsSnapshot
import com.buntap.app.ui.BunViewModel
import com.buntap.app.ui.MessageEffect
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.ConfirmDialog
import com.buntap.app.ui.components.EmptyState
import com.buntap.app.ui.components.Fmt
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.KeyValue
import com.buntap.app.ui.components.ScreenColumn
import com.buntap.app.ui.components.SectionCard
import com.buntap.app.ui.components.SessionControls
import com.buntap.app.ui.components.SessionHeader
import com.buntap.app.ui.components.StatGrid
import com.buntap.app.ui.components.StatusPill
import com.buntap.app.ui.components.SwitchRow
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import com.buntap.app.ui.theme.StatusColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MonitorViewModel(c: AppContainer) : BunViewModel(c) {
    val sessions: StateFlow<List<SessionEntity>> = c.history.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun clearHistory() = viewModelScope.launch { c.history.clear(); show("History cleared") }
    fun setStepMode(enabled: Boolean) = c.engine.setStepMode(enabled)
}

@Composable
fun MonitorScreen(nav: Navigator) {
    val vm = bunViewModel { MonitorViewModel(it) }
    val state by vm.engineState.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    MessageEffect(vm, snackbar)

    Scaffold(topBar = { BunTopBar("Execution monitor") }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Live") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
            }
            ScreenColumn(androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                if (tab == 0) {
                    SectionCard {
                        SessionHeader(state)
                        state.kind?.takeIf { it.label != state.sessionName }?.let { HelpText(it.label) }
                        SessionControls(state, vm::pause, vm::resume, vm::stop, vm::emergencyStop, vm::step)
                        if (state.status.isActive && state.kind == com.buntap.app.domain.model.SessionKind.ROUTINE) {
                            SwitchRow("Step by step", state.stepMode, vm::setStepMode, "Pause before every routine action.")
                        }
                    }
                    SectionCard(title = "Rate and progress") { DetailedStats(state.stats) }
                    state.lastDetection?.let { d ->
                        SectionCard(title = "Last detection") {
                            KeyValue("Target", d.targetName)
                            KeyValue("Score", Fmt.score(d.score))
                            KeyValue("Matched area", d.rect.toString())
                            KeyValue("Tap position", d.tapPoint?.let { "(${it.x}, ${it.y})" } ?: "—")
                            KeyValue("Time", "+${Fmt.millis(d.offsetMs)}")
                        }
                    }
                    SectionCard(title = "Events", subtitle = "Newest first") {
                        if (state.events.isEmpty()) HelpText("Events appear here while automation runs.")
                        state.events.asReversed().take(60).forEach { EventLine(it) }
                    }
                    SectionCard(title = "What the counters mean") {
                        HelpText(
                            "Scheduled: taps BunTap decided to perform. Dispatched: gestures Android accepted. Completed: gestures " +
                                "Android reported finished — this does not prove the app underneath reacted. Requested TPS is the " +
                                "schedule; observed TPS is measured from real completion times. Missed deadlines are taps that " +
                                "could not be delivered on time and were skipped instead of being sent in a burst.",
                        )
                    }
                } else {
                    if (sessions.isNotEmpty()) {
                        OutlinedButton(onClick = { confirmClear = true }) { Text("Clear history") }
                    }
                    SectionCard {
                        if (sessions.isEmpty()) EmptyState("No history yet", "Finished sessions are listed here.", BunIcons.History)
                        sessions.forEachIndexed { i, s ->
                            SessionRow(s) { nav.history(s.id) }
                            if (i < sessions.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog("Clear history?", "All recorded sessions and their logs will be deleted.", "Clear", vm::clearHistory, { confirmClear = false }, destructive = true)
    }
}

@Composable
fun DetailedStats(s: StatsSnapshot) {
    StatGrid(
        listOf(
            "Requested TPS" to Fmt.tps(s.requestedTps),
            "Observed TPS (now)" to Fmt.tps(s.recentObservedTps),
            "Observed TPS (avg)" to Fmt.tps(s.averageObservedTps),
            "Planned taps" to (s.plannedTaps?.toString() ?: "∞"),
            "Completed" to s.completed.toString(),
            "Remaining" to (s.remainingTaps?.toString() ?: "∞"),
            "Scheduled" to s.scheduled.toString(),
            "Dispatched" to s.dispatched.toString(),
            "Failed gestures" to s.failedTaps.toString(),
            "Skipped" to s.skipped.toString(),
            "Missed deadlines" to s.missedDeadlines.toString(),
            "Elapsed (active)" to Fmt.duration(s.activeElapsedMs),
            "Detections" to s.detections.toString(),
            "Ambiguous" to s.ambiguousDetections.toString(),
            "Timeouts" to s.detectionTimeouts.toString(),
            "Retries" to s.retries.toString(),
            "Failed actions" to s.failedActions.toString(),
        ),
    )
}

@Composable
fun EventLine(e: ExecutionEvent) {
    val color = when (e.type) {
        EventType.ERROR -> StatusColors.failed
        EventType.WARNING, EventType.AMBIGUOUS, EventType.TIMEOUT -> StatusColors.paused
        EventType.DETECTION, EventType.NOTIFY -> StatusColors.running
        EventType.CANCELLED -> StatusColors.idle
        else -> Color.Unspecified
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "+${Fmt.duration(e.offsetMs)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${e.type.name.lowercase().replace('_', ' ')}: ${e.message}",
            style = MaterialTheme.typography.bodySmall,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        )
    }
}

@Composable
private fun SessionRow(s: SessionEntity, onClick: () -> Unit) {
    val status = runCatching { RunStatus.valueOf(s.status) }.getOrDefault(RunStatus.STOPPED)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge)
            HelpText(
                "${Fmt.dateTime(s.startedAt)} · ${Fmt.duration(s.activeDurationMs)} · ${s.completedTaps} taps" +
                    (s.observedTps?.let { " · ${Fmt.tps(it)} TPS" } ?: ""),
            )
        }
        StatusPill(status)
    }
}
