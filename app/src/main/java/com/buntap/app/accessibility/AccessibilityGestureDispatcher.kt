package com.buntap.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.Looper
import com.buntap.app.automation.GestureDispatcher
import com.buntap.app.automation.GestureResult
import com.buntap.app.notifications.NotificationHelper
import com.buntap.app.routines.DeviceActionResult
import com.buntap.app.routines.DeviceActions
import com.buntap.app.routines.GlobalKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * [GestureDispatcher] backed by [BunTapAccessibilityService.dispatchTap]. It suspends until
 * Android calls `onCompleted`/`onCancelled`, so the scheduler never overlaps gestures.
 *
 * Note: COMPLETED means Android finished injecting the gesture. It does not prove that the
 * app underneath processed or responded to the tap.
 */
class AccessibilityGestureDispatcher(
    private val callbackHandler: Handler = Handler(Looper.getMainLooper()),
) : GestureDispatcher {

    override val available: StateFlow<Boolean> = AccessibilityBridge.connected

    override suspend fun tap(x: Int, y: Int, pressDurationMs: Long): GestureResult {
        val service = AccessibilityBridge.service ?: return GestureResult.UNAVAILABLE
        if (x < 0 || y < 0) return GestureResult.REJECTED
        val result = withTimeoutOrNull(pressDurationMs + CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                fun finish(r: GestureResult) {
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resume(r)
                }
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = finish(GestureResult.COMPLETED)
                    override fun onCancelled(gestureDescription: GestureDescription?) = finish(GestureResult.CANCELLED)
                }
                val accepted = try {
                    service.dispatchTap(x.toFloat(), y.toFloat(), pressDurationMs, callback, callbackHandler)
                } catch (e: IllegalArgumentException) {
                    false
                } catch (e: IllegalStateException) {
                    false
                }
                if (!accepted) finish(GestureResult.REJECTED)
            }
        }
        return result ?: GestureResult.TIMED_OUT
    }

    private companion object {
        /** How long to wait for Android's callback beyond the press duration. */
        const val CALLBACK_TIMEOUT_MS = 1_000L
    }
}

/** Routine key/text/notification actions. */
class AndroidDeviceActions(private val notifications: NotificationHelper) : DeviceActions {

    override suspend fun pressKey(key: GlobalKey): DeviceActionResult = withContext(Dispatchers.Main) {
        val s = AccessibilityBridge.service
            ?: return@withContext DeviceActionResult(false, "The accessibility service is not running")
        s.pressKey(key)
    }

    override suspend fun typeText(text: String, append: Boolean): DeviceActionResult = withContext(Dispatchers.Main) {
        val s = AccessibilityBridge.service
            ?: return@withContext DeviceActionResult(false, "The accessibility service is not running")
        s.typeText(text, append)
    }

    override fun showNotification(title: String, message: String): Boolean =
        notifications.showAlert(title, message)
}
