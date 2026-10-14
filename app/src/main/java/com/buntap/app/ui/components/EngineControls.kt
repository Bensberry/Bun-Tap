package com.buntap.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buntap.app.automation.EngineState
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.StatsSnapshot
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.theme.EmergencyRed

/** Big red button that cancels all automation immediately. */
@Composable
fun EmergencyStopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed, contentColor = Color.White),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("emergency_stop"),
    ) {
        Icon(BunIcons.Bolt, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("EMERGENCY STOP", fontWeight = FontWeight.Bold)
    }
}

/** Pause/Resume/Step/Stop for an active session plus the emergency stop. */
@Composable
fun SessionControls(
    state: EngineState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onEmergency: () -> Unit,
    onStep: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.status.isActive) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                when (state.status) {
                    RunStatus.RUNNING -> OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Icon(BunIcons.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause")
                    }
                    RunStatus.PAUSED -> {
                        if (state.stepMode && onStep != null) {
                            OutlinedButton(onClick = onStep, modifier = Modifier.weight(1f)) {
                                Icon(BunIcons.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Step")
                            }
                        }
                        OutlinedButton(onClick = onResume, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Resume")
                        }
                    }
                    else -> Unit
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                    Icon(BunIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            }
        }
        EmergencyStopButton(onEmergency)
    }
}

/** Status header: pill, name, current activity or result message. */
@Composable
fun SessionHeader(state: EngineState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                state.status,
                if (state.status == RunStatus.COUNTDOWN) "Starting in ${(state.countdownRemainingMs + 999) / 1000} s" else Fmt.status(state.status),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                state.sessionName ?: "No automation running",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        val detail = if (state.status.isActive) state.activity else state.message
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The key live numbers: requested vs observed rate, progress, elapsed time and failures. */
@Composable
fun CoreStats(stats: StatsSnapshot) {
    StatGrid(
        listOf(
            "Requested TPS" to Fmt.tps(stats.requestedTps),
            "Observed TPS" to Fmt.tps(stats.recentObservedTps ?: stats.averageObservedTps),
            "Average TPS" to Fmt.tps(stats.averageObservedTps),
            "Completed taps" to stats.completed.toString(),
            "Remaining" to (stats.remainingTaps?.toString() ?: "∞"),
            "Elapsed" to Fmt.duration(stats.activeElapsedMs),
        ),
    )
}
