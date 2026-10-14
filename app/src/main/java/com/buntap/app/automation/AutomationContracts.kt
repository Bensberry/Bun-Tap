package com.buntap.app.automation

import com.buntap.app.domain.model.ActionRunStatus
import com.buntap.app.domain.model.DetectionInfo
import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.ExecutionEvent
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.domain.model.StatsSnapshot
import com.buntap.app.domain.model.StopReason
import com.buntap.app.domain.model.TapRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * A unit of automation run by the [AutomationEngine]: the standard clicker, visual
 * automation, or a routine. Tasks never dispatch gestures directly; every tap goes
 * through [ExecutionContext.tapBatch], so all features share one scheduler.
 */
interface AutomationTask {
    val name: String
    val kind: SessionKind

    /** True when the task needs an active screen-capture session. */
    val requiresCapture: Boolean get() = false

    /** True when the task needs the accessibility service (taps, keys, text). */
    val needsGestures: Boolean get() = true

    /** Countdown before the task starts, so the user can switch apps. */
    val startDelayMs: Long get() = 0L

    /** Task-specific limit on active run time (the global safety limit always applies too). */
    val maxDurationMs: Long? get() = null

    /** Task-specific limit on successful taps. Reaching it ends the session as completed. */
    val tapLimit: Int? get() = null

    /** Planned successful taps for progress display; null when unknown or unlimited. */
    val plannedTaps: Int? get() = null

    /** Start in step-by-step debugging mode (routines). */
    val startInStepMode: Boolean get() = false

    suspend fun run(ctx: ExecutionContext): TaskOutcome
}

/** How a task finished when it was not stopped from outside. */
data class TaskOutcome(
    val success: Boolean,
    val message: String,
    val reason: StopReason = if (success) StopReason.COMPLETED else StopReason.ERROR,
)

/** Thrown inside a session to end it immediately with a specific reason. */
open class SessionEndException(val reason: StopReason, message: String) : Exception(message)

/** Global safety limits, read from settings when a session starts. */
data class SafetyLimits(
    val maxTps: Double = RateLimits.DEFAULT_MAX_TPS,
    /** Maximum dispatched gestures per session. */
    val maxTapsPerSession: Int = 10_000,
    /** Maximum active (unpaused) session time. */
    val maxSessionDurationMs: Long = 60 * 60_000L,
    /** How long each tap is held down. */
    val pressDurationMs: Long = RateLimits.DEFAULT_PRESS_DURATION_MS,
    /** Record every execution event in history (still never screenshots). */
    val diagnosticLogging: Boolean = false,
) {
    val rateLimits: RateLimits get() = RateLimits(maxTps = maxTps, pressDurationMs = pressDurationMs)
}

/** One tap batch: [count] taps at [rate] on ([x], [y]). */
data class TapBatchRequest(
    val label: String,
    val x: Int,
    val y: Int,
    /** Number of successful taps to perform; null = until stopped, a limit, or the timeout. */
    val count: Int?,
    val rate: TapRate,
    val preDelayMs: Long = 0,
    val postDelayMs: Long = 0,
    /** Active-time limit for the taps (excluding the pre/post delays). */
    val timeoutMs: Long? = null,
    /**
     * Screen the coordinates belong to. Checked before every tap: if the display changed
     * (e.g. rotation), the session ends instead of tapping a wrong position.
     */
    val reference: ScreenGeometry? = null,
)

enum class BatchOutcome { COMPLETED, TIMED_OUT, FAILED }

data class BatchResult(
    val outcome: BatchOutcome,
    val completedTaps: Int,
    val failedTaps: Int,
    val message: String? = null,
)

/** Counters tasks can increment. */
enum class StatCounter { AMBIGUOUS, DETECTION_TIMEOUT, RETRY, FAILED_ACTION }

/** Everything a running task may do. Implemented by the engine. */
interface ExecutionContext {
    val limits: SafetyLimits

    /** Monotonic milliseconds since the session started running (includes paused time). */
    fun elapsedMs(): Long

    /** Safe point: throws if cancelled, suspends while paused. Returns true if it waited for a pause. */
    suspend fun checkpoint(): Boolean

    /** Waits [ms] of active time (pausable and cancellable). */
    suspend fun sleep(ms: Long)

    /** Runs [block] with an active-time limit. */
    suspend fun <T> withTimeout(timeoutMs: Long, block: suspend CoroutineScope.() -> T): Timed<T>

    /** Performs a tap batch through the central scheduler. */
    suspend fun tapBatch(request: TapBatchRequest): BatchResult

    /** Called before each routine action; handles step-by-step mode. */
    suspend fun beforeAction(actionId: String, description: String)

    fun setActionStatus(actionId: String, status: ActionRunStatus)
    fun setActivity(text: String?)
    fun setRequestedRate(rate: TapRate?)
    fun recordDetection(info: DetectionInfo)
    fun increment(counter: StatCounter)
    fun log(type: EventType, message: String)

    /** The current screen geometry, or null when unknown. */
    fun currentGeometry(): ScreenGeometry?

    /**
     * Ends the session with [StopReason.DISPLAY_CHANGED] when the screen no longer matches
     * [reference], so coordinates picked on another screen layout are never tapped.
     */
    fun requireGeometry(reference: ScreenGeometry?)
}

/** Android-specific state the engine needs, abstracted for testing. */
interface EngineEnvironment {
    /** True while a screen-capture session is active. */
    val captureActive: StateFlow<Boolean>

    fun currentGeometry(): ScreenGeometry?

    /** True when ([x], [y]) is covered by one of BunTap's own overlay windows. */
    fun isCoveredByOwnOverlay(x: Int, y: Int): Boolean
}

/** Stores finished sessions (execution history). */
fun interface SessionRecorder {
    suspend fun record(record: SessionRecord)
}

data class SessionRecord(
    val kind: SessionKind,
    val name: String,
    val startedAtWallMs: Long,
    val endedAtWallMs: Long,
    val status: RunStatus,
    val reason: StopReason,
    val message: String?,
    val stats: StatsSnapshot,
    val events: List<ExecutionEvent>,
)

/** Whether [AutomationEngine.start] accepted a task. */
sealed interface StartResult {
    data object Started : StartResult
    data class Rejected(val reason: String) : StartResult
}

/** A task that can be prepared now and started later, e.g. from the floating panel. */
sealed interface PreparedTask {
    data class Ready(val task: AutomationTask) : PreparedTask
    data class Invalid(val message: String) : PreparedTask
}

/** The automation that the floating panel's Start button will run. */
data class ArmedTask(
    val name: String,
    val kind: SessionKind,
    val prepare: suspend () -> PreparedTask,
)

/** Everything the UI needs to show about the current (or last) session. */
data class EngineState(
    val status: RunStatus = RunStatus.IDLE,
    val sessionId: Long = 0,
    val sessionName: String? = null,
    val kind: SessionKind? = null,
    val countdownRemainingMs: Long = 0,
    val stepMode: Boolean = false,
    val stats: StatsSnapshot = StatsSnapshot(),
    val activity: String? = null,
    val lastDetection: DetectionInfo? = null,
    val actionStatuses: Map<String, ActionRunStatus> = emptyMap(),
    val currentActionId: String? = null,
    val events: List<ExecutionEvent> = emptyList(),
    val stopReason: StopReason? = null,
    val message: String? = null,
)
