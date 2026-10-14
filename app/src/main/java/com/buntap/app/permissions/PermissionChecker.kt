package com.buntap.app.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.buntap.app.accessibility.AccessibilityBridge
import com.buntap.app.accessibility.BunTapAccessibilityService
import com.buntap.app.capture.CaptureManager
import com.buntap.app.capture.DisplayMonitor
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.floating.OverlayPermission
import com.buntap.app.notifications.NotificationHelper
import com.buntap.app.vision.OpenCvRuntime

/** Everything the Permissions & diagnostics screen shows. */
data class PermissionSnapshot(
    /** The accessibility service is bound and can dispatch gestures right now. */
    val accessibilityConnected: Boolean,
    /** The user switched the service on in Settings (it may still be starting). */
    val accessibilityEnabledInSettings: Boolean,
    val overlayGranted: Boolean,
    val notificationsAllowed: Boolean,
    val captureActive: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val openCvLoaded: Boolean,
    val openCvStatus: String,
    val androidVersion: String,
    val sdkInt: Int,
    val device: String,
    val screen: ScreenGeometry,
)

class PermissionChecker(
    private val context: Context,
    private val capture: CaptureManager,
    private val display: DisplayMonitor,
    private val notifications: NotificationHelper,
) {
    fun snapshot(): PermissionSnapshot {
        val pm = context.getSystemService(PowerManager::class.java)
        OpenCvRuntime.ensureLoaded()
        return PermissionSnapshot(
            accessibilityConnected = AccessibilityBridge.connected.value,
            accessibilityEnabledInSettings = BunTapAccessibilityService.isEnabledInSettings(context),
            overlayGranted = OverlayPermission.granted(context),
            notificationsAllowed = notifications.canPost(),
            captureActive = capture.active.value,
            ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName),
            openCvLoaded = OpenCvRuntime.isLoaded,
            openCvStatus = OpenCvRuntime.statusMessage(),
            androidVersion = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            screen = display.refresh(),
        )
    }
}

/** Intents for the relevant system settings screens. */
object SystemSettings {
    fun accessibility(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** App info, where Android 13+ offers "Allow restricted settings" for sideloaded apps. */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notifications(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Battery optimization list (needs no special permission). */
    fun batteryOptimization(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
