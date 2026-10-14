package com.buntap.app.ui.navigation

import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf
import com.buntap.app.AppContainer

/** Activity-level operations that screens may trigger. Provided by [com.buntap.app.MainActivity]. */
class AppActions(
    /** Shows Android's screen-capture consent dialog. */
    val requestCapture: () -> Unit,
    /** Opens a system settings screen. */
    val openIntent: (Intent) -> Unit,
    /**
     * Shows the on-screen point picker and moves BunTap to the background so the user can
     * pick a position in the target app. Returns false if the overlay permission is missing.
     */
    val startPointPicker: (requestKey: String, label: String) -> Boolean,
    /** Moves BunTap to the background (e.g. before a delayed screenshot). */
    val moveToBackground: () -> Unit,
    /** Brings BunTap back to the front, optionally opening the crop screen. */
    val bringToFront: (cropPath: String?) -> Unit,
)

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

/** Requests coming from outside the UI (notifications, the floating panel). */
sealed interface ExternalRequest {
    data class Crop(val path: String) : ExternalRequest
    data object OpenMonitor : ExternalRequest
}
