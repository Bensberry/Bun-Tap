package com.buntap.app.testing

import com.buntap.app.automation.AutomationEngine
import com.buntap.app.automation.AutomationTask
import com.buntap.app.automation.EngineEnvironment
import com.buntap.app.automation.ExecutionContext
import com.buntap.app.automation.GestureDispatcher
import com.buntap.app.automation.GestureResult
import com.buntap.app.automation.MonotonicClock
import com.buntap.app.automation.SafetyLimits
import com.buntap.app.automation.SessionRecord
import com.buntap.app.automation.SessionRecorder
import com.buntap.app.automation.TaskOutcome
import com.buntap.app.domain.model.DetectionMode
import com.buntap.app.domain.model.MatchSettings
import com.buntap.app.domain.model.PixelPoint
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.domain.model.TapRate
import com.buntap.app.domain.model.TargetActionSettings
import com.buntap.app.domain.model.VisualTarget
import com.buntap.app.routines.DeviceActionResult
import com.buntap.app.routines.DeviceActions
import com.buntap.app.routines.GlobalKey
import com.buntap.app.vision.Detection
import com.buntap.app.vision.DetectionBatch
import com.buntap.app.vision.DetectionOutcome
import com.buntap.app.vision.TargetDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope

/**
 * Test double for the accessibility gesture API. It records every tap with the virtual time
 * at which it was dispatched and "holds" the gesture for the press duration using virtual time.
 * This verifies scheduling logic only — it is NOT proof of real device behaviour.
 */
class FakeGestureDispatcher(private val nowMs: () -> Long) : GestureDispatcher {
    data class Tap(val x: Int, val y: Int, val atMs: Long)

    override val available = MutableStateFlow(true)
    val taps = mutableListOf<Tap>()

    /** Results to return for the next gestures (FIFO); COMPLETED when empty. */
    val queuedResults = ArrayDeque<GestureResult>()

    /** Extra simulated latency per gesture, e.g. to model a slow device. */
    var extraLatencyMs = 0L

    /** One-off extra latencies for the next gestures (FIFO), e.g. a single slow callback. */
    val extraLatencyQueue = ArrayDeque<Long>()

    override suspend fun tap(x: Int, y: Int, pressDurationMs: Long): GestureResult {
        val result = queuedResults.removeFirstOrNull() ?: GestureResult.COMPLETED
        if (result == GestureResult.UNAVAILABLE || result == GestureResult.REJECTED) return result
        taps += Tap(x, y, nowMs())
        delay(pressDurationMs + extraLatencyMs + (extraLatencyQueue.removeFirstOrNull() ?: 0L))
        return result
    }
}

class TestEnvironment(var geometry: ScreenGeometry? = ScreenGeometry(1080, 2400)) : EngineEnvironment {
    override val captureActive = MutableStateFlow(true)
    var covered: PixelRect? = null
    override fun currentGeometry(): ScreenGeometry? = geometry
    override fun isCoveredByOwnOverlay(x: Int, y: Int): Boolean = covered?.contains(x, y) == true
}

class RecordingRecorder : SessionRecorder {
    val records = mutableListOf<SessionRecord>()
    override suspend fun record(record: SessionRecord) {
        records += record
    }
}

/** Scripted detector: [script] decides the outcome for each target at a given virtual time. */
class FakeDetector(
    private val nowMs: () -> Long,
    var script: (targetId: String, timeMs: Long) -> DetectionOutcome,
) : TargetDetector {
    var scans = 0
    override suspend fun detect(targets: List<VisualTarget>): DetectionBatch {
        scans++
        val t = nowMs()
        return DetectionBatch(targets.associate { it.id to script(it.id, t) }, frameGeometry = null)
    }
}

fun found(targetId: String, x: Int = 300, y: Int = 600, score: Float = 0.95f): DetectionOutcome =
    DetectionOutcome.Found(
        Detection(targetId, PixelRect(x - 20, y - 10, 40, 20), score, PixelPoint(x, y), null, 1),
    )

val notFound: DetectionOutcome = DetectionOutcome.NotFound(0.4f)

class FakeDeviceActions : DeviceActions {
    val keys = mutableListOf<GlobalKey>()
    val typed = mutableListOf<String>()
    val notifications = mutableListOf<Pair<String, String>>()
    var keyResult = DeviceActionResult(true, "ok")
    var typeResult = DeviceActionResult(true, "ok")

    override suspend fun pressKey(key: GlobalKey): DeviceActionResult {
        keys += key
        return keyResult
    }

    override suspend fun typeText(text: String, append: Boolean): DeviceActionResult {
        typed += text
        return typeResult
    }

    override fun showNotification(title: String, message: String): Boolean {
        notifications += title to message
        return true
    }
}

fun testTarget(
    id: String,
    mode: DetectionMode = DetectionMode.ONCE_PER_APPEARANCE,
    tps: Double = 5.0,
    match: MatchSettings = MatchSettings(scanIntervalMs = 100),
    action: TargetActionSettings = TargetActionSettings(mode = mode, rate = TapRate.fromTps(tps), cooldownMs = 0),
): VisualTarget = VisualTarget(
    id = id,
    name = "Target $id",
    imageFile = "$id.png",
    width = 40,
    height = 20,
    createdAt = 0,
    updatedAt = 0,
    match = match,
    action = action,
)

/** A task defined inline for engine tests. */
fun testTask(
    name: String = "test",
    needsGestures: Boolean = true,
    requiresCapture: Boolean = false,
    startDelayMs: Long = 0,
    tapLimit: Int? = null,
    maxDurationMs: Long? = null,
    body: suspend (ExecutionContext) -> TaskOutcome,
): AutomationTask = object : AutomationTask {
    override val name = name
    override val kind = SessionKind.CLICKER
    override val needsGestures = needsGestures
    override val requiresCapture = requiresCapture
    override val startDelayMs = startDelayMs
    override val tapLimit = tapLimit
    override val maxDurationMs = maxDurationMs
    override suspend fun run(ctx: ExecutionContext): TaskOutcome = body(ctx)
}

/** Everything needed to drive an engine on virtual time. */
class EngineHarness(
    scope: TestScope,
    limits: SafetyLimits = SafetyLimits(),
    val env: TestEnvironment = TestEnvironment(),
) {
    private val scheduler = scope.testScheduler
    val nowMs: () -> Long = { scheduler.currentTime }
    val gestures = FakeGestureDispatcher(nowMs)
    val recorder = RecordingRecorder()
    var limits: SafetyLimits = limits
    val engine = AutomationEngine(
        scope = scope,
        gestures = gestures,
        clock = MonotonicClock { scheduler.currentTime * 1_000_000L },
        limitsProvider = { this.limits },
        recorder = recorder,
        environment = env,
        wallClock = { scheduler.currentTime },
    )
}
