package com.buntap.app.automation

import kotlinx.coroutines.flow.StateFlow

/** What Android reported for one tap gesture. */
enum class GestureResult {
    /** `onCompleted` was called: the gesture finished. The target app may still ignore it. */
    COMPLETED,

    /** `onCancelled` was called (e.g. another gesture or a user touch interrupted it). */
    CANCELLED,

    /** `dispatchGesture` returned false: Android refused to dispatch it. */
    REJECTED,

    /** The accessibility service is not connected. */
    UNAVAILABLE,

    /** Dispatched, but no completion callback arrived in time. */
    TIMED_OUT;

    /** True when Android accepted the gesture for dispatch. */
    val wasDispatched: Boolean get() = this == COMPLETED || this == CANCELLED || this == TIMED_OUT
}

/** Performs tap gestures. Implemented with an AccessibilityService on devices; faked in tests. */
interface GestureDispatcher {
    /** True while gestures can be dispatched (the accessibility service is connected). */
    val available: StateFlow<Boolean>

    /** Taps at screen coordinates ([x], [y]), holding for [pressDurationMs]. Suspends until Android reports back. */
    suspend fun tap(x: Int, y: Int, pressDurationMs: Long): GestureResult
}
