package com.buntap.app.ui.permissions

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.capture.CaptureState
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.floating.OverlayPermission
import com.buntap.app.permissions.PermissionSnapshot
import com.buntap.app.permissions.SystemSettings
import com.buntap.app.ui.BunViewModel
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.Fmt
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.KeyValue
import com.buntap.app.ui.components.ScreenColumn
import com.buntap.app.ui.components.SectionCard
import com.buntap.app.ui.components.StatusPill
import com.buntap.app.ui.navigation.LocalAppActions
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PermissionsViewModel(c: AppContainer) : BunViewModel(c) {
    private val _snapshot = MutableStateFlow<PermissionSnapshot?>(null)
    val snapshot: StateFlow<PermissionSnapshot?> = _snapshot
    val capture = c.capture.state

    private val _frameInfo = MutableStateFlow("")
    val frameInfo: StateFlow<String> = _frameInfo

    fun refresh() {
        viewModelScope.launch(Dispatchers.Default) {
            _snapshot.value = c.permissions.snapshot()
            val age = c.capture.lastFrameAgeMs
            _frameInfo.value = "${c.capture.frameCount} frames received" +
                (age?.let { ", last ${Fmt.millis(it)} ago (static screens produce no new frames)" } ?: "")
        }
    }

    fun stopCapture() = c.capture.stop()
}

@Composable
fun PermissionsScreen(nav: Navigator) {
    val vm = bunViewModel { PermissionsViewModel(it) }
    val actions = LocalAppActions.current
    val snapshot by vm.snapshot.collectAsState()
    val capture by vm.capture.collectAsState()
    val frameInfo by vm.frameInfo.collectAsState()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(topBar = { BunTopBar("Permissions & diagnostics", onBack = nav::back) }) { padding ->
        ScreenColumn(padding) {
            val s = snapshot ?: return@ScreenColumn

            SectionCard(
                title = "Accessibility service",
                subtitle = "Required for tapping, key presses and typing",
                trailing = {
                    StatusPill(
                        if (s.accessibilityConnected) RunStatus.RUNNING else if (s.accessibilityEnabledInSettings) RunStatus.PAUSED else RunStatus.FAILED,
                        when {
                            s.accessibilityConnected -> "Connected"
                            s.accessibilityEnabledInSettings -> "Starting"
                            else -> "Off"
                        },
                    )
                },
            ) {
                HelpText(
                    "Open Accessibility settings, find “BunTap automation” under installed or downloaded apps, and turn it on. " +
                        "BunTap never turns it on by itself and only uses it for the actions you configure.",
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    HelpText(
                        "If Android shows “Restricted setting” (common for apps installed from an APK file): open App info, " +
                            "tap the ⋮ menu, choose “Allow restricted settings”, then try again.",
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.openIntent(SystemSettings.accessibility()) }) { Text("Accessibility settings") }
                    OutlinedButton(onClick = { actions.openIntent(SystemSettings.appDetails(context)) }) { Text("App info") }
                }
            }

            SectionCard(
                title = "Screen capture",
                subtitle = "Only needed for visual targets",
                trailing = {
                    StatusPill(
                        if (capture is CaptureState.Active) RunStatus.RUNNING else RunStatus.IDLE,
                        if (capture is CaptureState.Active) "Active" else "Off",
                    )
                },
            ) {
                HelpText(
                    "Android asks for permission every time capture starts (choose “Entire screen” if asked). Capture stops when " +
                        "the screen locks, when you stop it from the status bar, or when BunTap's process ends. Frames are analysed " +
                        "on this device only. Protected screens appear black and cannot be recognised.",
                )
                (capture as? CaptureState.Ended)?.let { HelpText("Last session: ${it.reason}") }
                if (capture is CaptureState.Active) {
                    HelpText(frameInfo)
                    OutlinedButton(onClick = vm::stopCapture) { Text("Stop screen capture") }
                } else {
                    Button(onClick = actions.requestCapture) { Text("Start screen capture") }
                }
            }

            SectionCard(
                title = "Display over other apps",
                subtitle = "Optional: floating panel and on-screen point picker",
                trailing = { StatusPill(if (s.overlayGranted) RunStatus.RUNNING else RunStatus.IDLE, if (s.overlayGranted) "Allowed" else "Not allowed") },
            ) {
                HelpText("Without it, use the in-app controls, the notification, and coordinate entry or screenshot picking instead.")
                if (!s.overlayGranted) Button(onClick = { actions.openIntent(OverlayPermission.settingsIntent(context)) }) { Text("Allow") }
            }

            SectionCard(
                title = "Notifications",
                subtitle = "Optional: status with Pause/Stop buttons and routine messages",
                trailing = { StatusPill(if (s.notificationsAllowed) RunStatus.RUNNING else RunStatus.IDLE, if (s.notificationsAllowed) "Allowed" else "Off") },
            ) {
                if (!s.notificationsAllowed) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            actions.openIntent(SystemSettings.notifications(context))
                        }
                    }) { Text("Allow notifications") }
                    HelpText("If the request does not appear, enable notifications for BunTap in system settings.")
                }
            }

            SectionCard(
                title = "Battery optimization",
                subtitle = "Background limits can interrupt long automations",
                trailing = {
                    StatusPill(
                        if (s.ignoringBatteryOptimizations) RunStatus.RUNNING else RunStatus.PAUSED,
                        if (s.ignoringBatteryOptimizations) "Unrestricted" else "Optimized",
                    )
                },
            ) {
                HelpText(
                    "Android and some phone makers stop background apps to save battery. If automation stops unexpectedly, " +
                        "set BunTap to “Unrestricted”/“Don't optimize”. Even then, Android does not guarantee indefinite execution.",
                )
                OutlinedButton(onClick = { actions.openIntent(SystemSettings.batteryOptimization()) }) { Text("Battery settings") }
            }

            SectionCard(title = "Diagnostics") {
                KeyValue("Image recognition", s.openCvStatus)
                KeyValue("Android", "${s.androidVersion} (API ${s.sdkInt})")
                KeyValue("Device", s.device)
                KeyValue("Screen", "${s.screen.describe()}, rotation ${s.screen.rotation * 90}°, ${s.screen.densityDpi} dpi")
                KeyValue("Accessibility bound", if (s.accessibilityConnected) "Yes" else "No")
                OutlinedButton(onClick = vm::refresh) { Text("Refresh") }
            }
        }
    }
}
