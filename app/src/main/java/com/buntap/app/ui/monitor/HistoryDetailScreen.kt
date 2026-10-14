package com.buntap.app.ui.monitor

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.data.db.SessionEntity
import com.buntap.app.data.db.SessionEventEntity
import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.ExecutionEvent
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.StatsSnapshot
import com.buntap.app.domain.model.StopReason
import com.buntap.app.ui.BunViewModel
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.Fmt
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.KeyValue
import com.buntap.app.ui.components.ScreenColumn
import com.buntap.app.ui.components.SectionCard
import com.buntap.app.ui.components.StatusPill
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryDetailViewModel(c: AppContainer, sessionId: Long) : BunViewModel(c) {
    val session: StateFlow<SessionEntity?> = c.history.session(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val events: StateFlow<List<SessionEventEntity>> = c.history.events(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun HistoryDetailScreen(nav: Navigator, sessionId: Long) {
    val vm = bunViewModel(key = "history:$sessionId") { HistoryDetailViewModel(it, sessionId) }
    val session by vm.session.collectAsState()
    val events by vm.events.collectAsState()
    Scaffold(topBar = { BunTopBar(session?.name ?: "Session", onBack = nav::back) }) { padding ->
        ScreenColumn(padding) {
            val s = session ?: return@ScreenColumn
            SectionCard(trailing = { StatusPill(runCatching { RunStatus.valueOf(s.status) }.getOrDefault(RunStatus.STOPPED)) }, title = s.name) {
                KeyValue("Started", Fmt.dateTime(s.startedAt))
                KeyValue("Finished", Fmt.dateTime(s.endedAt))
                KeyValue("Result", runCatching { StopReason.valueOf(s.stopReason).label }.getOrDefault(s.stopReason))
                s.message?.let { HelpText(it) }
            }
            SectionCard(title = "Statistics") {
                DetailedStats(
                    StatsSnapshot(
                        requestedTps = s.requestedTps,
                        averageObservedTps = s.observedTps,
                        plannedTaps = s.plannedTaps,
                        scheduled = s.scheduledTaps,
                        dispatched = s.dispatchedTaps,
                        completed = s.completedTaps,
                        cancelled = s.cancelledTaps,
                        rejected = s.rejectedTaps,
                        timedOut = s.timedOutTaps,
                        skipped = s.skippedTaps,
                        missedDeadlines = s.missedDeadlines,
                        detections = s.detections,
                        ambiguousDetections = s.ambiguousDetections,
                        detectionTimeouts = s.detectionTimeouts,
                        retries = s.retries,
                        failedActions = s.failedActions,
                        activeElapsedMs = s.activeDurationMs,
                    ),
                )
            }
            SectionCard(title = "Log (${events.size})") {
                if (events.isEmpty()) HelpText("No events recorded. Turn on detailed logging in Settings for a full log.")
                events.forEach { e ->
                    EventLine(ExecutionEvent(e.offsetMs, runCatching { EventType.valueOf(e.type) }.getOrDefault(EventType.INFO), e.message))
                }
            }
        }
    }
}
