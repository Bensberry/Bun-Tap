package com.buntap.app.routines

import com.buntap.app.automation.AutomationTask
import com.buntap.app.automation.BatchOutcome
import com.buntap.app.automation.ExecutionContext
import com.buntap.app.automation.StatCounter
import com.buntap.app.automation.TapBatchRequest
import com.buntap.app.automation.TaskOutcome
import com.buntap.app.automation.Timed
import com.buntap.app.domain.model.ActionRunStatus
import com.buntap.app.domain.model.DetectionInfo
import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.PixelPoint
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.domain.model.StopReason
import com.buntap.app.domain.model.VisualTarget
import com.buntap.app.vision.Detection
import com.buntap.app.vision.DetectionOutcome
import com.buntap.app.vision.TapPositionResolver
import com.buntap.app.vision.TargetDetector
import java.util.Locale

/** Non-tap device interactions used by routines (implemented with the accessibility service). */
interface DeviceActions {
    suspend fun pressKey(key: GlobalKey): DeviceActionResult
    suspend fun typeText(text: String, append: Boolean): DeviceActionResult

    /** Returns false when notifications are blocked for BunTap. */
    fun showNotification(title: String, message: String): Boolean
}

data class DeviceActionResult(val success: Boolean, val message: String)

/**
 * Executes a routine deterministically: actions run one after another, groups recurse, every
 * loop and retry is bounded, and every wait is cancellable and pausable. Pausing takes effect
 * at safe boundaries (before each action, between taps, and during waits).
 */
class RoutineTask(
    private val routine: Routine,
    private val targets: Map<String, VisualTarget>,
    private val detector: TargetDetector,
    private val device: DeviceActions,
    override val startDelayMs: Long = 0,
    stepMode: Boolean = false,
) : AutomationTask {

    override val name: String = routine.name
    override val kind: SessionKind = SessionKind.ROUTINE
    override val requiresCapture: Boolean = RoutineValidator.usesTargets(routine.body)
    override val needsGestures: Boolean = RoutineValidator.usesAccessibility(routine.body)
    override val maxDurationMs: Long? = routine.body.maxDurationMs
    override val tapLimit: Int? = routine.body.maxTotalTaps
    override val startInStepMode: Boolean = stepMode

    private val defaults = routine.body.defaults
    private val lastDetections = HashMap<String, Detection>()
    private val warnedUnavailable = HashSet<String>()

    /** Result of one action or list of actions. */
    sealed interface Step {
        data object Success : Step

        /** "Skip the rest of this group": ends the innermost group successfully. */
        data object SkipRest : Step
        data class Failure(val message: String) : Step
        data class Stop(val success: Boolean, val message: String) : Step
    }

    override suspend fun run(ctx: ExecutionContext): TaskOutcome {
        if (RoutineValidator.usesFixedCoordinates(routine.body)) ctx.requireGeometry(routine.body.reference)
        return when (val r = runList(ctx, routine.body.actions)) {
            Step.Success, Step.SkipRest -> TaskOutcome(true, "Routine “${routine.name}” finished")
            is Step.Failure -> TaskOutcome(false, r.message, StopReason.ROUTINE_FAILED)
            is Step.Stop -> TaskOutcome(
                success = r.success,
                message = r.message.ifBlank { if (r.success) "Stopped by a Stop action" else "Stopped by a Stop action (failure)" },
                reason = if (r.success) StopReason.COMPLETED else StopReason.ROUTINE_FAILED,
            )
        }
    }

    private fun targetName(id: String): String = targets[id]?.name ?: "missing target"

    private fun describe(a: RoutineAction): String =
        "${RoutineDescriber.title(a)}: ${RoutineDescriber.summary(a, defaults, ::targetName)}"

    private suspend fun runList(ctx: ExecutionContext, list: List<RoutineAction>): Step {
        for (a in list) {
            if (!a.enabled) {
                ctx.setActionStatus(a.id, ActionRunStatus.SKIPPED)
                continue
            }
            val description = describe(a)
            ctx.beforeAction(a.id, description)
            ctx.setActionStatus(a.id, ActionRunStatus.RUNNING)
            ctx.log(EventType.ACTION, description)
            when (val result = execute(ctx, a)) {
                Step.Success -> ctx.setActionStatus(a.id, ActionRunStatus.SUCCEEDED)
                Step.SkipRest -> {
                    ctx.setActionStatus(a.id, ActionRunStatus.SUCCEEDED)
                    return Step.SkipRest
                }
                is Step.Failure -> {
                    ctx.setActionStatus(a.id, ActionRunStatus.FAILED)
                    ctx.increment(StatCounter.FAILED_ACTION)
                    if (a.onFailure == FailurePolicy.CONTINUE) {
                        ctx.log(EventType.WARNING, "${RoutineDescriber.title(a)} failed (continuing): ${result.message}")
                    } else {
                        ctx.log(EventType.ERROR, "${RoutineDescriber.title(a)} failed: ${result.message}")
                        return result
                    }
                }
                is Step.Stop -> {
                    ctx.setActionStatus(a.id, ActionRunStatus.SUCCEEDED)
                    return result
                }
            }
        }
        return Step.Success
    }

    private suspend fun execute(ctx: ExecutionContext, a: RoutineAction): Step = when (a) {
        is WaitAction -> {
            ctx.sleep(a.durationMs)
            Step.Success
        }

        is TapPointAction -> {
            ctx.requireGeometry(routine.body.reference)
            tap(ctx, "(${a.x}, ${a.y})", PixelPoint(a.x, a.y), a.taps, a.preDelayMs, a.postDelayMs, a.timeoutMs, routine.body.reference)
        }

        is FindTargetAction -> findStep(ctx, a.targetId, a.timeoutMs)

        is WaitForTargetAction -> findStep(ctx, a.targetId, a.timeoutMs)

        is WaitUntilGoneAction -> waitUntilGone(ctx, a)

        is TapTargetAction -> tapTarget(ctx, a)

        is PressKeyAction -> {
            val r = device.pressKey(a.key)
            if (r.success) Step.Success else Step.Failure(r.message)
        }

        is TypeTextAction -> {
            val r = device.typeText(a.text, a.append)
            // Never log the text itself: it might be sensitive.
            if (r.success) {
                ctx.log(EventType.INFO, "Typed ${a.text.length} characters")
                Step.Success
            } else {
                Step.Failure(r.message)
            }
        }

        is RepeatAction -> {
            var result: Step = Step.Success
            for (i in 1..a.times) {
                ctx.setActivity("Repeat ${i}/${a.times}")
                when (val r = runList(ctx, a.actions)) {
                    Step.Success, Step.SkipRest -> Unit
                    else -> {
                        result = r
                        break
                    }
                }
            }
            result
        }

        is IfTargetAction -> {
            val timeout = a.timeoutMs ?: defaults.timeoutMs
            val present = find(ctx, a.targetId, timeout, countTimeout = false) != null
            val takeThen = present == a.expectPresent
            ctx.log(
                EventType.INFO,
                "“${targetName(a.targetId)}” ${if (present) "found" else "not found"} → ${if (takeThen) "Then" else "Else"} branch",
            )
            when (val r = runList(ctx, if (takeThen) a.thenActions else a.elseActions)) {
                Step.SkipRest -> Step.Success
                else -> r
            }
        }

        is ContinueIfTargetAction -> {
            val timeout = a.timeoutMs ?: defaults.timeoutMs
            if (find(ctx, a.targetId, timeout, countTimeout = false) != null) {
                Step.Success
            } else {
                val msg = "“${targetName(a.targetId)}” not found"
                when (a.whenMissing) {
                    MissingTargetBehavior.STOP_SUCCESS -> Step.Stop(true, "$msg; routine stopped")
                    MissingTargetBehavior.STOP_FAILURE -> Step.Stop(false, "$msg; routine stopped")
                    MissingTargetBehavior.SKIP_REST_OF_GROUP -> {
                        ctx.log(EventType.INFO, "$msg; skipping the rest of this group")
                        Step.SkipRest
                    }
                }
            }
        }

        is RetryAction -> retry(ctx, a)

        is TimeLimitAction -> when (val r = ctx.withTimeout(a.timeoutMs) { runList(ctx, a.actions) }) {
            is Timed.Done -> if (r.value == Step.SkipRest) Step.Success else r.value
            Timed.TimedOut -> {
                ctx.log(EventType.TIMEOUT, "Time limit of ${RoutineDescriber.seconds(a.timeoutMs)} expired")
                Step.Failure("Time limit of ${RoutineDescriber.seconds(a.timeoutMs)} expired")
            }
        }

        is StopAction -> Step.Stop(a.success, a.message)

        is NotifyAction -> {
            if (!device.showNotification(a.title, a.message)) {
                ctx.log(EventType.WARNING, "Notification not shown: notifications are turned off for BunTap")
            }
            ctx.log(EventType.NOTIFY, "${a.title}: ${a.message}")
            Step.Success
        }

        is LogAction -> {
            ctx.log(EventType.LOG, a.message)
            Step.Success
        }
    }

    private suspend fun findStep(ctx: ExecutionContext, targetId: String, timeoutMs: Long?): Step {
        val t = timeoutMs ?: defaults.timeoutMs
        return if (find(ctx, targetId, t) != null) {
            Step.Success
        } else {
            Step.Failure("“${targetName(targetId)}” was not found within ${RoutineDescriber.seconds(t)}")
        }
    }

    private suspend fun retry(ctx: ExecutionContext, a: RetryAction): Step {
        var lastFailure: Step.Failure? = null
        for (attempt in 1..a.maxAttempts) {
            if (attempt > 1) {
                ctx.increment(StatCounter.RETRY)
                ctx.log(EventType.RETRY, "Attempt $attempt of ${a.maxAttempts} (previous: ${lastFailure?.message})")
                ctx.sleep(a.delayBetweenMs)
            }
            when (val r = runList(ctx, a.actions)) {
                Step.Success, Step.SkipRest -> return Step.Success
                is Step.Stop -> return r
                is Step.Failure -> lastFailure = r
            }
        }
        return Step.Failure("Failed after ${a.maxAttempts} attempt(s): ${lastFailure?.message}")
    }

    private suspend fun tapTarget(ctx: ExecutionContext, a: TapTargetAction): Step {
        val target = targets[a.targetId] ?: return Step.Failure("The target no longer exists")
        val timeout = a.timeoutMs ?: defaults.timeoutMs
        val detection = (if (a.useLastDetection) lastDetections[a.targetId] else null)
            ?: find(ctx, a.targetId, timeout)
            ?: return Step.Failure("“${target.name}” was not found within ${RoutineDescriber.seconds(timeout)}")
        val geometry = ctx.currentGeometry()
        if (detection.geometry != null && geometry != null && !detection.geometry.sameSize(geometry)) {
            return Step.Failure("The screen changed since “${target.name}” was detected")
        }
        val point = if (a.anchor != null) {
            TapPositionResolver.resolve(detection.rect, a.anchor, a.offsetX, a.offsetY)
        } else {
            PixelPoint(detection.tapPoint.x + a.offsetX, detection.tapPoint.y + a.offsetY)
        }
        return tap(ctx, target.name, point, a.taps, a.preDelayMs, a.postDelayMs, null, detection.geometry)
    }

    private suspend fun tap(
        ctx: ExecutionContext,
        label: String,
        point: PixelPoint,
        spec: TapSpec,
        preDelayMs: Long,
        postDelayMs: Long,
        timeoutMs: Long?,
        reference: ScreenGeometry?,
    ): Step {
        val result = ctx.tapBatch(
            TapBatchRequest(
                label = label,
                x = point.x,
                y = point.y,
                count = spec.count ?: defaults.tapCount,
                rate = spec.rate ?: defaults.rate,
                preDelayMs = preDelayMs,
                postDelayMs = postDelayMs,
                timeoutMs = timeoutMs,
                reference = reference,
            ),
        )
        return when (result.outcome) {
            BatchOutcome.COMPLETED -> Step.Success
            BatchOutcome.TIMED_OUT -> Step.Failure(result.message ?: "Tapping timed out")
            BatchOutcome.FAILED -> Step.Failure(result.message ?: "Tapping failed")
        }
    }

    /** Searches until found or [timeoutMs] of active time passes (0 = one scan). */
    private suspend fun find(
        ctx: ExecutionContext,
        targetId: String,
        timeoutMs: Long,
        countTimeout: Boolean = true,
    ): Detection? {
        val target = targets[targetId] ?: return null
        if (timeoutMs <= 0) return scanOnce(ctx, target)
        val result = ctx.withTimeout<Detection>(timeoutMs) {
            while (true) {
                val d = scanOnce(ctx, target)
                if (d != null) return@withTimeout d
                ctx.sleep(target.match.scanIntervalMs)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        return when (result) {
            is Timed.Done -> result.value
            Timed.TimedOut -> {
                if (countTimeout) ctx.increment(StatCounter.DETECTION_TIMEOUT)
                null
            }
        }
    }

    private suspend fun scanOnce(ctx: ExecutionContext, target: VisualTarget): Detection? {
        ctx.checkpoint()
        val batch = detector.detect(listOf(target))
        return when (val outcome = batch.outcomes[target.id]) {
            is DetectionOutcome.Found -> {
                val d = outcome.detection
                lastDetections[target.id] = d
                ctx.recordDetection(DetectionInfo(target.id, target.name, d.score, d.rect, d.tapPoint, ctx.elapsedMs()))
                ctx.log(
                    EventType.DETECTION,
                    String.format(Locale.US, "Found “%s” (score %.3f) at %s", target.name, d.score, d.rect),
                )
                warnedUnavailable.remove(target.id)
                d
            }
            is DetectionOutcome.Ambiguous -> {
                ctx.increment(StatCounter.AMBIGUOUS)
                ctx.log(EventType.AMBIGUOUS, "“${target.name}”: ${outcome.reason}")
                null
            }
            is DetectionOutcome.Unavailable -> {
                if (warnedUnavailable.add(target.id)) {
                    ctx.log(EventType.WARNING, "“${target.name}”: ${batch.frameProblem ?: outcome.reason}")
                }
                null
            }
            is DetectionOutcome.NotFound, null -> null
        }
    }

    private suspend fun waitUntilGone(ctx: ExecutionContext, a: WaitUntilGoneAction): Step {
        val target = targets[a.targetId] ?: return Step.Failure("The target no longer exists")
        val timeout = a.timeoutMs ?: defaults.timeoutMs
        val result = ctx.withTimeout(timeout) {
            var misses = 0
            while (true) {
                ctx.checkpoint()
                when (detector.detect(listOf(target)).outcomes[target.id]) {
                    is DetectionOutcome.NotFound -> {
                        misses++
                        // Two consecutive misses confirm the disappearance (filters flicker).
                        if (misses >= 2) return@withTimeout true
                    }
                    is DetectionOutcome.Found -> misses = 0
                    else -> Unit // unknown frames neither confirm nor deny
                }
                ctx.sleep(target.match.scanIntervalMs)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        return when (result) {
            is Timed.Done -> Step.Success
            Timed.TimedOut -> {
                ctx.increment(StatCounter.DETECTION_TIMEOUT)
                Step.Failure("“${target.name}” was still visible after ${RoutineDescriber.seconds(timeout)}")
            }
        }
    }
}
