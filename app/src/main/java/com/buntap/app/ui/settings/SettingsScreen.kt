package com.buntap.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.BuildConfig
import com.buntap.app.domain.NumberInput
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.floating.FloatingPanelService
import com.buntap.app.floating.OverlayPermission
import com.buntap.app.settings.AppSettings
import com.buntap.app.settings.ThemeMode
import com.buntap.app.ui.BunViewModel
import com.buntap.app.ui.MessageEffect
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.ConfirmDialog
import com.buntap.app.ui.components.Fmt
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.IntField
import com.buntap.app.ui.components.KeyValue
import com.buntap.app.ui.components.MillisField
import com.buntap.app.ui.components.RateEditor
import com.buntap.app.ui.components.ScreenColumn
import com.buntap.app.ui.components.SecondsField
import com.buntap.app.ui.components.SectionCard
import com.buntap.app.ui.components.SegmentedChoice
import com.buntap.app.ui.components.SliderField
import com.buntap.app.ui.components.SwitchRow
import com.buntap.app.ui.navigation.LocalAppActions
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class StorageInfo(
    val targets: Int,
    val targetBytes: Long,
    val routines: Int,
    val sessions: Int,
    val tempBytes: Long,
)

class SettingsViewModel(c: AppContainer) : BunViewModel(c) {
    val settings = c.settings.settings
    val panelRunning = FloatingPanelService.running

    private val _storage = MutableStateFlow<StorageInfo?>(null)
    val storage: StateFlow<StorageInfo?> = _storage

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { c.settings.update(transform) }
    }

    fun setPanel(enabled: Boolean) {
        if (enabled && !OverlayPermission.granted(c.appContext)) {
            show("Allow “Display over other apps” first (Permissions screen).")
            return
        }
        update { it.copy(floatingPanelEnabled = enabled) }
        if (enabled) FloatingPanelService.start(c.appContext) else FloatingPanelService.stop(c.appContext)
    }

    fun loadStorage() {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) {
                StorageInfo(c.targets.count(), c.images.totalBytes(), c.routines.count(), c.history.count(), c.images.tempBytes())
            }
        }
    }

    fun clearHistory() = viewModelScope.launch { c.history.clear(); loadStorage(); show("History cleared") }
    fun deleteAllTargets() = viewModelScope.launch { c.targets.deleteAll(); c.vision.clearCache(); loadStorage(); show("All targets deleted") }
    fun deleteAllRoutines() = viewModelScope.launch { c.routines.deleteAll(); c.engine.disarm(); loadStorage(); show("All routines deleted") }
    fun clearTemp() = viewModelScope.launch {
        withContext(Dispatchers.IO) { c.images.clearTemp() }
        loadStorage()
        show("Temporary screenshots deleted")
    }
}

@Composable
fun SettingsScreen(nav: Navigator) {
    val vm = bunViewModel { SettingsViewModel(it) }
    val loaded by vm.settings.collectAsState()
    val panel by vm.panelRunning.collectAsState()
    val storage by vm.storage.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val actions = LocalAppActions.current
    var confirm by remember { mutableStateOf<String?>(null) }
    MessageEffect(vm, snackbar)
    LaunchedEffect(Unit) { vm.loadStorage() }

    Scaffold(topBar = { BunTopBar("Settings", onBack = nav::back) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val s = loaded ?: return@Scaffold
        ScreenColumn(padding) {
            SectionCard(title = "Default timing", subtitle = "Used for new tap points, targets and routines") {
                RateEditor(
                    rate = s.defaultRate,
                    limits = s.rateLimits,
                    onRateChange = { r -> vm.update { it.copy(defaultTps = r.tps) } },
                    title = "Default rate",
                )
                IntField("Default tap count", s.defaultTapCount, { v -> vm.update { it.copy(defaultTapCount = v) } }, 1..1_000_000)
                MillisField(
                    "Tap press duration",
                    s.pressDurationMs,
                    { v -> vm.update { it.copy(pressDurationMs = v) } },
                    1L..200L,
                    supporting = "How long each tap is held. Very short taps are ignored by some apps; long ones limit the maximum rate.",
                )
                SecondsField(
                    "Start countdown",
                    s.startDelayMs,
                    { v -> vm.update { it.copy(startDelayMs = v) } },
                    0L..60_000L,
                    supporting = "Time to switch to the target app after pressing Start.",
                )
            }

            SectionCard(title = "Safety limits", subtitle = "Apply to every automation, including routines") {
                var maxTpsText by remember(s.maxTps) { mutableStateOf(Fmt.tps(s.maxTps)) }
                androidx.compose.material3.OutlinedTextField(
                    value = maxTpsText,
                    onValueChange = { t ->
                        maxTpsText = t
                        NumberInput.parseDouble(t)?.takeIf { it in 1.0..RateLimits.HARD_MAX_TPS }?.let { v -> vm.update { it.copy(maxTps = v) } }
                    },
                    label = { Text("Maximum taps per second") },
                    supportingText = { Text("1–${RateLimits.HARD_MAX_TPS.toInt()} TPS. Rates above ~20 TPS are rarely sustained by Android.") },
                    isError = NumberInput.parseDouble(maxTpsText)?.let { it !in 1.0..RateLimits.HARD_MAX_TPS } ?: true,
                    singleLine = true,
                )
                IntField("Maximum taps per session", s.maxTapsPerSession, { v -> vm.update { it.copy(maxTapsPerSession = v) } }, 1..1_000_000)
                IntField(
                    "Maximum session duration",
                    s.maxSessionMinutes,
                    { v -> vm.update { it.copy(maxSessionMinutes = v) } },
                    1..1440,
                    suffix = "min",
                    supporting = "Counts active (unpaused) time.",
                )
            }

            SectionCard(title = "Floating panel") {
                SwitchRow("Show floating panel", panel, vm::setPanel, "Start/pause/stop and emergency stop over other apps. Needs “Display over other apps”.")
                SwitchRow(
                    "Show automatically when taps start",
                    s.panelAutoShow,
                    { v -> vm.update { it.copy(panelAutoShow = v) } },
                    "Appears over the app you automate, with pause, stop, add tap point and add picture target.",
                )
                SwitchRow("Show live tap count and TPS", s.panelShowStats, { v -> vm.update { it.copy(panelShowStats = v) } })
                if (!OverlayPermission.granted(androidx.compose.ui.platform.LocalContext.current)) {
                    OutlinedButton(onClick = nav::permissions) { Text("Open Permissions") }
                }
            }

            SectionCard(title = "Visual recognition") {
                SliderField(
                    "Processing resolution",
                    s.processingScale,
                    { v -> vm.update { it.copy(processingScale = (v * 20).roundToInt() / 20f) } },
                    0.25f..1f,
                    { Fmt.percent(it) },
                    supporting = "Lower is faster and uses less battery; higher finds small targets more reliably. Small targets automatically use a higher resolution.",
                )
                SliderField(
                    "Default confidence threshold",
                    s.defaultThreshold,
                    { v -> vm.update { it.copy(defaultThreshold = (v * 100).roundToInt() / 100f) } },
                    0.5f..0.99f,
                    { Fmt.score(it) },
                )
                MillisField("Default scan interval", s.defaultScanIntervalMs, { v -> vm.update { it.copy(defaultScanIntervalMs = v) } }, 100L..10_000L)
            }

            SectionCard(title = "Appearance") {
                SegmentedChoice(ThemeMode.entries.map { it to it.label }, s.theme, { v -> vm.update { it.copy(theme = v) } })
            }

            SectionCard(title = "Diagnostics") {
                SwitchRow(
                    "Detailed execution log",
                    s.diagnosticLogging,
                    { v -> vm.update { it.copy(diagnosticLogging = v) } },
                    "Records every tap batch, detection and action in history (text only — never screenshots).",
                )
            }

            SectionCard(title = "Storage") {
                storage?.let { st ->
                    KeyValue("Visual targets", "${st.targets} (${Fmt.bytes(st.targetBytes)})")
                    KeyValue("Routines", st.routines.toString())
                    KeyValue("History sessions", st.sessions.toString())
                    KeyValue("Temporary screenshots", Fmt.bytes(st.tempBytes))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { confirm = "history" }) { Text("Clear execution history") }
                    OutlinedButton(onClick = vm::clearTemp) { Text("Delete temporary screenshots") }
                    OutlinedButton(onClick = { confirm = "targets" }) { Text("Delete all visual targets") }
                    OutlinedButton(onClick = { confirm = "routines" }) { Text("Delete all routines") }
                }
            }

            SectionCard(title = "About") {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.buntap.app.R.drawable.buntap_logo),
                        contentDescription = "BunTap logo",
                        modifier = androidx.compose.ui.Modifier.size(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                    )
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.size(14.dp))
                    Column {
                        Text("BunTap", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                        Text("Made by bensberry", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    }
                }
                KeyValue("Version", BuildConfig.VERSION_NAME)
                HelpText(
                    "BunTap works entirely on this device: it has no internet permission, no accounts, no ads and no analytics. " +
                        "Screenshots are processed in memory; only the cropped target images you save are stored.",
                )
                OutlinedButton(onClick = nav::onboarding) { Text("Show introduction again") }
                OutlinedButton(onClick = { actions.openIntent(com.buntap.app.permissions.SystemSettings.accessibility()) }) {
                    Text("Accessibility settings")
                }
            }
        }
    }

    when (confirm) {
        "history" -> ConfirmDialog("Clear history?", "All recorded sessions and their logs will be deleted.", "Clear", vm::clearHistory, { confirm = null }, destructive = true)
        "targets" -> ConfirmDialog(
            "Delete all targets?",
            "Every saved visual target and its image will be deleted. Routines that use them will no longer run.",
            "Delete all", vm::deleteAllTargets, { confirm = null }, destructive = true,
        )
        "routines" -> ConfirmDialog("Delete all routines?", "Every routine in the library will be deleted. This cannot be undone.", "Delete all", vm::deleteAllRoutines, { confirm = null }, destructive = true)
    }
}
