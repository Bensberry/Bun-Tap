package com.buntap.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.buntap.app.routines.DeviceActionResult
import com.buntap.app.routines.GlobalKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BunTap's accessibility service. It is used ONLY to:
 *  - perform tap gestures that the user configured (dispatchGesture),
 *  - press system keys from routines (global actions such as Back/Home),
 *  - type text into the focused field when a routine contains a "Type text" action.
 *
 * It does not read, store, or transmit the content of other apps. The user must enable it
 * manually in Android Settings → Accessibility; BunTap never enables it programmatically.
 */
class BunTapAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: BunTap does not observe other apps' content.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }

    /**
     * Dispatches a single-point tap held for [durationMs]. Returns false if Android refused
     * to dispatch it. The result arrives later through [callback] on [handler].
     */
    fun dispatchTap(x: Float, y: Float, durationMs: Long, callback: GestureResultCallback, handler: Handler): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val duration = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
        return dispatchGesture(gesture, callback, handler)
    }

    @SuppressLint("InlinedApi") // GLOBAL_ACTION_LOCK_SCREEN (API 28) is only reached after the SDK check below.
    fun pressKey(key: GlobalKey): DeviceActionResult {
        if (Build.VERSION.SDK_INT < key.minSdk) {
            return DeviceActionResult(false, "${key.label} needs Android API ${key.minSdk} or newer")
        }
        if (key == GlobalKey.ENTER) return pressImeEnter()
        val action = when (key) {
            GlobalKey.BACK -> GLOBAL_ACTION_BACK
            GlobalKey.HOME -> GLOBAL_ACTION_HOME
            GlobalKey.RECENTS -> GLOBAL_ACTION_RECENTS
            GlobalKey.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            GlobalKey.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            GlobalKey.LOCK_SCREEN -> GLOBAL_ACTION_LOCK_SCREEN
            GlobalKey.ENTER -> error("handled above")
        }
        return if (performGlobalAction(action)) {
            DeviceActionResult(true, "Pressed ${key.label}")
        } else {
            DeviceActionResult(false, "Android did not perform ${key.label}")
        }
    }

    private fun pressImeEnter(): DeviceActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return DeviceActionResult(false, "Enter needs Android 11 or newer")
        }
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return DeviceActionResult(false, "No text field is focused")
        val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return if (ok) DeviceActionResult(true, "Pressed Enter") else DeviceActionResult(false, "The focused field did not accept Enter")
    }

    /** Sets (or appends to) the text of the currently focused editable field. */
    fun typeText(text: String, append: Boolean): DeviceActionResult {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return DeviceActionResult(false, "No text field is focused. Tap a text field first.")
        if (!node.isEditable) return DeviceActionResult(false, "The focused element is not an editable text field")
        if (append && node.isPassword) return DeviceActionResult(false, "Cannot append to a password field")
        val newText = if (append) (node.text?.toString().orEmpty()) + text else text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) DeviceActionResult(true, "Typed text") else DeviceActionResult(false, "The text field did not accept the text")
    }

    companion object {
        /** True if the user has enabled the service in Settings (it may not be bound yet). */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, BunTapAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** Process-wide link to the running accessibility service (Android creates the service). */
object AccessibilityBridge {
    @Volatile
    var service: BunTapAccessibilityService? = null
        private set

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    internal fun attach(s: BunTapAccessibilityService) {
        service = s
        _connected.value = true
    }

    internal fun detach(s: BunTapAccessibilityService) {
        if (service === s) {
            service = null
            _connected.value = false
        }
    }
}
