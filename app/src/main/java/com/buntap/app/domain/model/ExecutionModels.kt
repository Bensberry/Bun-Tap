package com.buntap.app.domain.model

/** Lifecycle of an automation session. */
enum class RunStatus {
    IDLE,
    COUNTDOWN,
    RUNNING,
    PAUSED,
    STOPPING,
    COMPLETED,
    STOPPED,
    FAILED;

    /** True while a session exists and may still dispatch taps. */
    val isActive: Boolean get() = this == COUNTDOWN || this == RUNNING || this == PAUSED || this == STOPPING
}

/** The three automation features. All of them run through the same engine. */
enum class SessionKind(val label: String) {
    CLICKER("Auto clicker"),
    VISUAL("Visual automation"),
    ROUTINE("Routine"),
}

/** Why a session ended. */
enum class StopReason(val label: String) {
    COMPLETED("Completed"),
    USER_STOP("Stopped by user"),
    EMERGENCY_STOP("Emergency stop"),
    TAP_LIMIT("Tap limit reached"),
    DURATION_LIMIT("Duration limit reached"),
    ACCESSIBILITY_LOST("Accessibility service unavailable"),
    CAPTURE_LOST("Screen capture ended"),
    DISPLAY_CHANGED("Screen size or orientation changed"),
    ROUTINE_FAILED("Routine failed"),
    ERROR("Error"),
}

/** Status of one routine action, shown in the editor and monitor. */
enum class ActionRunStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

/** Categories of execution events (log entries). */
enum class EventType {
    INFO,
    TAP_BATCH,
    DETECTION,
    DETECTION_LOST,
    AMBIGUOUS,
    TIMEOUT,
    RETRY,
    ACTION,
    NOTIFY,
    LOG,
    WARNING,
    ERROR,
    CANCELLED;

    /** Events kept in history even when detailed diagnostics are off. */
    val isImportant: Boolean
        get() = this == TIMEOUT || this == RETRY || this == WARNING || this == ERROR ||
            this == CANCELLED || this == AMBIGUOUS || this == NOTIFY || this == LOG || this == INFO ||
            this == DETECTION || this == DETECTION_LOST
}

/** One log entry; [offsetMs] is measured from the session start with a monotonic clock. */
data class ExecutionEvent(val offsetMs: Long, val type: EventType, val message: String)

/** Latest detection, for the monitor and diagnostics. */
data class DetectionInfo(
    val targetId: String,
    val targetName: String,
    val score: Float,
    val rect: PixelRect,
    val tapPoint: PixelPoint?,
    val offsetMs: Long,
)

/**
 * Live execution statistics. The tap counters deliberately distinguish each stage:
 *
 * - [scheduled]: tap actions BunTap decided to perform.
 * - [dispatched]: gestures Android accepted (`dispatchGesture` returned true).
 * - [completed]: gestures Android reported as finished (`onCompleted`). This does NOT prove
 *   the target app reacted to the tap.
 * - [cancelled] / [timedOut]: dispatched gestures that were cancelled or never reported back.
 * - [rejected]: gestures Android refused to dispatch.
 * - [skipped]: taps not attempted (e.g. the point was covered by BunTap's floating panel).
 */
data class StatsSnapshot(
    val requestedTps: Double? = null,
    val recentObservedTps: Double? = null,
    val averageObservedTps: Double? = null,
    /** Planned number of successful taps; null when unlimited or not known in advance. */
    val plannedTaps: Int? = null,
    val scheduled: Int = 0,
    val dispatched: Int = 0,
    val completed: Int = 0,
    val cancelled: Int = 0,
    val rejected: Int = 0,
    val timedOut: Int = 0,
    val skipped: Int = 0,
    val missedDeadlines: Int = 0,
    val detections: Int = 0,
    val ambiguousDetections: Int = 0,
    val detectionTimeouts: Int = 0,
    val retries: Int = 0,
    val failedActions: Int = 0,
    val activeElapsedMs: Long = 0,
) {
    val remainingTaps: Int? get() = plannedTaps?.let { (it - completed).coerceAtLeast(0) }
    val failedTaps: Int get() = cancelled + rejected + timedOut
}
