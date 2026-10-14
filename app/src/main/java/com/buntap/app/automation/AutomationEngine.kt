package com.buntap.app.automation

import com.buntap.app.domain.model.ActionRunStatus
import com.buntap.app.domain.model.DetectionInfo
import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.ExecutionEvent
import com.buntap.app.domain.model.GeometryCompatibility
import com.buntap.app.domain.model.GeometryRules
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.StatsSnapshot
import com.buntap.app.domain.model.StopReason
import com.buntap.app.domain.model.TapRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one and only automation engine. It runs at most one [AutomationTask] at a time, so the
 * clicker, visual automation and routines can never start competing tap loops.
 *
 * Every session runs in two coroutines: an *inner* job that executes the task (cancelled by
 * stop/emergency stop) and an *outer* job that waits for it and then records the result.
 */
class AutomationEngine(
    private val scope: CoroutineScope,
    private val gestures: GestureDispatcher,
    private val clock: MonotonicClock,
    private val limitsProvider: () -> SafetyLimits,
    private val recorder: SessionRecorder,
    private val environment: EngineEnvironment,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val publishIntervalMs: Long = 200,
) {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _armed = MutableStateFlow<ArmedTask?>(null)

    /** The automation the floating panel's Start button runs. */
    val armed: StateFlow<ArmedTask?> = _armed.asStateFlow()

    private val lock = Any()
    private var current: Session? = null
    private var nextSessionId = 1L

    /** Set by emergency stop; checked immediately before every gesture as a last barrier. */
    @Volatile
    private var killSwitch = false

    val isRunning: Boolean get() = synchronized(lock) { current != null }

    fun arm(task: ArmedTask) {
        _armed.value = task
    }

    fun disarm() {
        _armed.value = null
    }

    /** Starts the armed automation (floating panel). */
    suspend fun startArmed(): StartResult {
        val armedTask = _armed.value
            ?: return StartResult.Rejected("Nothing is armed. Choose an automation in BunTap and tap “Arm floating panel”.")
        return when (val prepared = armedTask.prepare()) {
            is PreparedTask.Invalid -> StartResult.Rejected(prepared.message)
            is PreparedTask.Ready -> start(prepared.task)
        }
    }

    /** Starts [task] unless another session is active or a requirement is missing. */
    fun start(task: AutomationTask): StartResult {
        synchronized(lock) {
            if (current != null) {
                return StartResult.Rejected("Another automation is already running. Stop it first.")
            }
            if (task.needsGestures && !gestures.available.value) {
                return StartResult.Rejected(
                    "The BunTap accessibility service is off. Turn it on in Permissions & diagnostics.",
                )
            }
            if (task.requiresCapture && !environment.captureActive.value) {
                return StartResult.Rejected("Screen capture is not running. Start screen capture first.")
            }
            killSwitch = false
            val limits = limitsProvider()
            val session = Session(nextSessionId++, task, limits)
            current = session
            _state.value = EngineState(
                status = if (task.startDelayMs > 0) RunStatus.COUNTDOWN else RunStatus.RUNNING,
                sessionId = session.id,
                sessionName = task.name,
                kind = task.kind,
                countdownRemainingMs = task.startDelayMs,
                stepMode = session.stepMode,
                stats = StatsSnapshot(plannedTaps = task.plannedTaps),
            )
            val inner = scope.launch(start = CoroutineStart.LAZY) { session.result = execute(session) }
            session.innerJob = inner
            scope.launch {
                inner.start()
                inner.join()
                finish(session)
            }
            return StartResult.Started
        }
    }

    fun pause() {
        val s = synchronized(lock) {
            val s = current ?: return
            if (_state.value.status != RunStatus.RUNNING) return
            s.pauseInternal()
            s
        }
        _state.update { if (it.sessionId == s.id) it.copy(status = RunStatus.PAUSED) else it }
        s.log(EventType.INFO, "Paused")
    }

    fun resume() {
        val s = synchronized(lock) { current } ?: return
        if (!s.gate.isPaused) return
        s.resumeInternal()
        _state.update { if (it.status == RunStatus.PAUSED) it.copy(status = RunStatus.RUNNING) else it }
        s.log(EventType.INFO, "Resumed")
    }

    /** In step mode: runs the next action, then pauses again. */
    fun step() = resume()

    fun setStepMode(enabled: Boolean) {
        val s = synchronized(lock) { current } ?: return
        s.stepMode = enabled
        _state.update { it.copy(stepMode = enabled) }
    }

    /** Stops the current session. No new taps are scheduled once the stop is processed. */
    fun stop(reason: StopReason = StopReason.USER_STOP) {
        val s = synchronized(lock) { current } ?: return
        s.requestStop(reason, null)
        _state.update { if (it.sessionId == s.id && it.status.isActive) it.copy(status = RunStatus.STOPPING) else it }
    }

    /**
     * Emergency stop: cancels all pending automation immediately and blocks any gesture that
     * is about to be dispatched. A gesture already handed to Android (at most the press
     * duration, ~10 ms by default) cannot be recalled.
     */
    fun emergencyStop() {
        killSwitch = true
        stop(StopReason.EMERGENCY_STOP)
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun execute(s: Session): SessionResult? {
        val ctx = s.context
        return try {
            countdown(s)
            s.markRunning()
            _state.update { if (it.sessionId == s.id) it.copy(status = RunStatus.RUNNING, countdownRemainingMs = 0) else it }
            s.log(EventType.INFO, "Started ${s.task.name}")
            coroutineScope {
                val publisher = launch {
                    while (isActive) {
                        publish(s)
                        delay(publishIntervalMs)
                    }
                }
                val watchers = launch { watchRequirements(s) }
                try {
                    val maxDuration = listOfNotNull(s.task.maxDurationMs, s.limits.maxSessionDurationMs).min()
                    when (val r = ctx.withTimeout(maxDuration) { s.task.run(ctx) }) {
                        is Timed.Done -> SessionResult(r.value.reason, r.value.message)
                        Timed.TimedOut -> SessionResult(
                            StopReason.DURATION_LIMIT,
                            "Stopped after the maximum active duration of ${maxDuration / 1000} s",
                        )
                    }
                } finally {
                    publisher.cancel()
                    watchers.cancel()
                }
            }
        } catch (e: SessionEndException) {
            SessionResult(e.reason, e.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionResult(StopReason.ERROR, "Unexpected error: ${e.message ?: e::class.java.simpleName}")
        }
    }

    private suspend fun countdown(s: Session) {
        val total = s.task.startDelayMs
        if (total <= 0) return
        val endNs = clock.nanos() + total * 1_000_000L
        while (true) {
            val remainingMs = (endNs - clock.nanos()) / 1_000_000L
            if (remainingMs <= 0) break
            _state.update { if (it.sessionId == s.id) it.copy(countdownRemainingMs = remainingMs) else it }
            delay(minOf(remainingMs, 100L))
        }
    }

    /** Ends the session if the accessibility service or screen capture disappears. */
    private suspend fun watchRequirements(s: Session) = coroutineScope {
        if (s.task.needsGestures) {
            launch {
                gestures.available.first { !it }
                s.requestStop(
                    StopReason.ACCESSIBILITY_LOST,
                    "The BunTap accessibility service was turned off or stopped by Android",
                )
            }
        }
        if (s.task.requiresCapture) {
            launch {
                environment.captureActive.first { !it }
                s.requestStop(
                    StopReason.CAPTURE_LOST,
                    "Screen capture ended (stopped, revoked, or the screen was locked)",
                )
            }
        }
    }

    private fun publish(s: Session) {
        val snapshot = s.stats.snapshot(s.activeElapsedMs())
        val events = s.uiEvents()
        _state.update { if (it.sessionId == s.id) it.copy(stats = snapshot, events = events) else it }
    }

    private suspend fun finish(s: Session) {
        val result = s.result ?: SessionResult(
            s.requestedStop ?: StopReason.USER_STOP,
            s.stopMessage ?: defaultMessage(s.requestedStop ?: StopReason.USER_STOP),
        )
        val status = when (result.reason) {
            StopReason.COMPLETED -> RunStatus.COMPLETED
            StopReason.USER_STOP, StopReason.EMERGENCY_STOP, StopReason.TAP_LIMIT,
            StopReason.DURATION_LIMIT, StopReason.DISPLAY_CHANGED -> RunStatus.STOPPED
            else -> RunStatus.FAILED
        }
        val eventType = when (status) {
            RunStatus.FAILED -> EventType.ERROR
            RunStatus.STOPPED -> EventType.CANCELLED
            else -> EventType.INFO
        }
        s.log(eventType, result.message ?: result.reason.label)
        val snapshot = s.stats.snapshot(s.activeElapsedMs())
        synchronized(lock) {
            if (current === s) current = null
        }
        _state.update {
            if (it.sessionId != s.id) it else it.copy(
                status = status,
                stats = snapshot,
                events = s.uiEvents(),
                countdownRemainingMs = 0,
                activity = null,
                currentActionId = null,
                stopReason = result.reason,
                message = result.message ?: result.reason.label,
            )
        }
        try {
            recorder.record(
                SessionRecord(
                    kind = s.task.kind,
                    name = s.task.name,
                    startedAtWallMs = s.startWallMs,
                    endedAtWallMs = wallClock(),
                    status = status,
                    reason = result.reason,
                    message = result.message,
                    stats = snapshot,
                    events = s.historyEvents(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // History is best-effort; a storage failure must never break automation control.
        }
    }

    private fun defaultMessage(reason: StopReason): String = when (reason) {
        StopReason.USER_STOP -> "Stopped by user"
        StopReason.EMERGENCY_STOP -> "Emergency stop: all pending automation was cancelled"
        else -> reason.label
    }

    private data class SessionResult(val reason: StopReason, val message: String?)

    // ---------------------------------------------------------------------------------------

    private inner class Session(val id: Long, val task: AutomationTask, val limits: SafetyLimits) {
        val startWallMs: Long = wallClock()
        private var runningSinceNs: Long? = null
        private var pausedTotalNs = 0L
        private var pausedAtNs: Long? = null
        private val timeLock = Any()

        val gate = PauseGate()
        val timer = ActiveTimer(clock, gate)
        val stats = StatsTracker(task.plannedTaps)
        val context = EngineContext(this)

        @Volatile var stepMode: Boolean = task.startInStepMode
        @Volatile var innerJob: Job? = null
        @Volatile var result: SessionResult? = null
        @Volatile var requestedStop: StopReason? = null
        @Volatile var stopMessage: String? = null

        private val eventLock = Any()
        private val recentEvents = ArrayDeque<ExecutionEvent>()
        private val recorded = ArrayList<ExecutionEvent>()

        fun markRunning() {
            synchronized(timeLock) { runningSinceNs = clock.nanos() }
        }

        fun elapsedMs(): Long {
            val since = synchronized(timeLock) { runningSinceNs } ?: return 0
            return (clock.nanos() - since) / 1_000_000L
        }

        fun activeElapsedMs(): Long = synchronized(timeLock) {
            val since = runningSinceNs ?: return 0
            val now = clock.nanos()
            val pausedNow = pausedAtNs?.let { now - it } ?: 0L
            ((now - since - pausedTotalNs - pausedNow) / 1_000_000L).coerceAtLeast(0)
        }

        fun pauseInternal() {
            synchronized(timeLock) { if (pausedAtNs == null) pausedAtNs = clock.nanos() }
            gate.pause()
        }

        fun resumeInternal() {
            synchronized(timeLock) {
                pausedAtNs?.let { pausedTotalNs += clock.nanos() - it }
                pausedAtNs = null
            }
            gate.resume()
        }

        fun requestStop(reason: StopReason, message: String?) {
            if (requestedStop == null) {
                requestedStop = reason
                stopMessage = message
            }
            innerJob?.cancel()
        }

        fun log(type: EventType, message: String) {
            val event = ExecutionEvent(elapsedMs(), type, message)
            synchronized(eventLock) {
                recentEvents.addLast(event)
                if (recentEvents.size > UI_EVENT_LIMIT) recentEvents.removeFirst()
                val keep = limits.diagnosticLogging || type.isImportant
                val cap = if (limits.diagnosticLogging) DIAGNOSTIC_EVENT_LIMIT else HISTORY_EVENT_LIMIT
                if (keep && recorded.size < cap) recorded.add(event)
            }
        }

        fun uiEvents(): List<ExecutionEvent> = synchronized(eventLock) { recentEvents.toList() }
        fun historyEvents(): List<ExecutionEvent> = synchronized(eventLock) { recorded.toList() }
    }

    private inner class EngineContext(private val s: Session) : ExecutionContext, TapScheduler.Host {

        private val scheduler = TapScheduler(clock, gestures, s.stats, s.timer, s.limits, this)

        override val limits: SafetyLimits get() = s.limits

        override fun elapsedMs(): Long = s.elapsedMs()

        override suspend fun checkpoint(): Boolean {
            currentCoroutineContext().ensureActive()
            return s.gate.awaitResumed()
        }

        override suspend fun sleep(ms: Long) {
            if (ms <= 0) {
                checkpoint()
                return
            }
            s.stats.breakContinuity()
            s.timer.delayActive(ms * 1_000_000L)
        }

        override suspend fun <T> withTimeout(timeoutMs: Long, block: suspend CoroutineScope.() -> T): Timed<T> =
            s.timer.withActiveTimeout(timeoutMs, block)

        override suspend fun tapBatch(request: TapBatchRequest): BatchResult {
            val result = scheduler.run(request)
            if (s.limits.diagnosticLogging || result.outcome != BatchOutcome.COMPLETED) {
                val type = if (result.outcome == BatchOutcome.COMPLETED) EventType.TAP_BATCH else EventType.WARNING
                log(
                    type,
                    "${request.label}: ${result.completedTaps} taps completed, ${result.failedTaps} failed" +
                        (result.message?.let { " — $it" } ?: ""),
                )
            }
            return result
        }

        override suspend fun beforeAction(actionId: String, description: String) {
            currentCoroutineContext().ensureActive()
            _state.update { if (it.sessionId == s.id) it.copy(currentActionId = actionId) else it }
            if (s.stepMode) {
                s.pauseInternal()
                _state.update {
                    if (it.sessionId == s.id) it.copy(status = RunStatus.PAUSED, activity = "Step mode — next: $description") else it
                }
            }
            if (s.gate.awaitResumed()) {
                _state.update { if (it.sessionId == s.id && it.status == RunStatus.PAUSED) it.copy(status = RunStatus.RUNNING) else it }
            }
            setActivity(description)
        }

        override fun setActionStatus(actionId: String, status: ActionRunStatus) {
            _state.update {
                if (it.sessionId == s.id) it.copy(actionStatuses = it.actionStatuses + (actionId to status)) else it
            }
        }

        override fun setActivity(text: String?) {
            _state.update { if (it.sessionId == s.id) it.copy(activity = text) else it }
        }

        override fun setRequestedRate(rate: TapRate?) {
            s.stats.update { it.copy(requestedTps = rate?.tps) }
        }

        override fun recordDetection(info: DetectionInfo) {
            s.stats.update { it.copy(detections = it.detections + 1) }
            _state.update { if (it.sessionId == s.id) it.copy(lastDetection = info) else it }
        }

        override fun increment(counter: StatCounter) {
            s.stats.update {
                when (counter) {
                    StatCounter.AMBIGUOUS -> it.copy(ambiguousDetections = it.ambiguousDetections + 1)
                    StatCounter.DETECTION_TIMEOUT -> it.copy(detectionTimeouts = it.detectionTimeouts + 1)
                    StatCounter.RETRY -> it.copy(retries = it.retries + 1)
                    StatCounter.FAILED_ACTION -> it.copy(failedActions = it.failedActions + 1)
                }
            }
        }

        override fun log(type: EventType, message: String) = s.log(type, message)

        override fun currentGeometry(): ScreenGeometry? = environment.currentGeometry()

        override fun requireGeometry(reference: ScreenGeometry?) {
            val current = environment.currentGeometry()
            when (val c = GeometryRules.compare(reference, current)) {
                GeometryCompatibility.Same, GeometryCompatibility.Unknown -> Unit
                else -> throw SessionEndException(
                    StopReason.DISPLAY_CHANGED,
                    GeometryRules.explain(c) ?: "The screen changed while running",
                )
            }
        }

        // TapScheduler.Host --------------------------------------------------------------

        override fun preflight(x: Int, y: Int, reference: ScreenGeometry?): String? {
            if (killSwitch) throw CancellationException("Emergency stop")
            requireGeometry(reference)
            s.task.tapLimit?.let { limit ->
                if (s.stats.completed >= limit) {
                    throw SessionEndException(
                        StopReason.COMPLETED,
                        "Reached the session tap limit ($limit successful taps)",
                    )
                }
            }
            if (s.stats.dispatched >= s.limits.maxTapsPerSession) {
                throw SessionEndException(
                    StopReason.TAP_LIMIT,
                    "Safety limit reached: ${s.limits.maxTapsPerSession} taps per session (change it in Settings)",
                )
            }
            val geometry = environment.currentGeometry()
            if (geometry != null && !geometry.contains(x, y)) {
                return "position ($x, $y) is outside the screen (${geometry.describe()})"
            }
            if (environment.isCoveredByOwnOverlay(x, y)) {
                return "position ($x, $y) is covered by BunTap's floating panel — move or collapse the panel"
            }
            return null
        }
    }

    private companion object {
        const val UI_EVENT_LIMIT = 100
        const val HISTORY_EVENT_LIMIT = 300
        const val DIAGNOSTIC_EVENT_LIMIT = 2_000
    }
}
