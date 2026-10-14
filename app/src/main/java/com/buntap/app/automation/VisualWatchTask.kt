package com.buntap.app.automation

import com.buntap.app.domain.model.DetectionInfo
import com.buntap.app.domain.model.DetectionMode
import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.domain.model.VisualTarget
import com.buntap.app.vision.DetectionOutcome
import com.buntap.app.vision.DetectionTracker
import com.buntap.app.vision.TargetDetector
import java.util.Locale

/**
 * Visual automation: repeatedly scans the screen for the given targets and reacts according
 * to each target's detection mode. Scans happen only between tap batches, so a detected
 * target's batch runs as one controlled operation and is never restarted by later scans.
 */
class VisualWatchTask(
    private val targets: List<VisualTarget>,
    private val detector: TargetDetector,
    override val name: String = "Visual automation",
    override val startDelayMs: Long = 0,
) : AutomationTask {

    override val kind: SessionKind = SessionKind.VISUAL
    override val requiresCapture: Boolean = true
    override val needsGestures: Boolean = targets.any { it.action.mode != DetectionMode.NOTIFY_ONLY }

    override suspend fun run(ctx: ExecutionContext): TaskOutcome {
        if (targets.isEmpty()) return TaskOutcome(false, "No enabled visual targets to watch")
        val start = ctx.elapsedMs()
        val trackers = targets.associate { it.id to DetectionTracker(DetectionTracker.Config.from(it), start) }
        val nextScanAt = targets.associate { it.id to start }.toMutableMap()
        val lastProblem = HashMap<String, String>()
        ctx.log(EventType.INFO, "Watching ${targets.size} target(s): ${targets.joinToString { it.name }}")

        while (true) {
            ctx.checkpoint()
            val active = targets.filter { !trackers.getValue(it.id).finished }
            if (active.isEmpty()) return finalOutcome(trackers)

            val now = ctx.elapsedMs()
            // Oldest scan first, so a constantly tapping target cannot starve the others.
            val due = active.filter { nextScanAt.getValue(it.id) <= now }
                .sortedBy { nextScanAt.getValue(it.id) }
            if (due.isEmpty()) {
                ctx.sleep(active.minOf { nextScanAt.getValue(it.id) } - now)
                continue
            }

            ctx.setActivity("Scanning for ${due.joinToString { "“${it.name}”" }}")
            val batch = detector.detect(due)
            batch.frameProblem?.let { problem ->
                if (lastProblem["frame"] != problem) {
                    ctx.log(EventType.WARNING, "Frame skipped: $problem")
                    lastProblem["frame"] = problem
                }
            } ?: lastProblem.remove("frame")

            for (target in due) {
                val tracker = trackers.getValue(target.id)
                val outcome = batch.outcomes[target.id] ?: DetectionOutcome.Unavailable("no result")
                val t = ctx.elapsedMs()
                nextScanAt[target.id] = t + target.match.scanIntervalMs

                val observation = when (outcome) {
                    is DetectionOutcome.Found -> tracker.observe(t, present = true)
                    is DetectionOutcome.NotFound -> tracker.observe(t, present = false)
                    is DetectionOutcome.Ambiguous -> {
                        ctx.increment(StatCounter.AMBIGUOUS)
                        ctx.log(EventType.AMBIGUOUS, "“${target.name}”: ${outcome.reason}")
                        tracker.observeUnknown(t)
                    }
                    is DetectionOutcome.Unavailable -> {
                        if (batch.frameProblem == null && lastProblem[target.id] != outcome.reason) {
                            ctx.log(EventType.WARNING, "“${target.name}”: ${outcome.reason}")
                            lastProblem[target.id] = outcome.reason
                        }
                        tracker.observeUnknown(t)
                    }
                }

                val found = (outcome as? DetectionOutcome.Found)?.detection
                when (observation.change) {
                    DetectionTracker.Change.APPEARED -> if (found != null) {
                        ctx.recordDetection(
                            DetectionInfo(target.id, target.name, found.score, found.rect, found.tapPoint, t),
                        )
                        ctx.log(
                            EventType.DETECTION,
                            String.format(Locale.US, "“%s” appeared (score %.3f) at %s", target.name, found.score, found.rect),
                        )
                    }
                    DetectionTracker.Change.DISAPPEARED ->
                        ctx.log(EventType.DETECTION_LOST, "“${target.name}” disappeared")
                    null -> Unit
                }

                when (val decision = observation.decision) {
                    is DetectionTracker.Decision.Tap -> {
                        val detection = found ?: continue
                        val geometry = ctx.currentGeometry()
                        if (detection.geometry != null && geometry != null && !detection.geometry.sameSize(geometry)) {
                            ctx.log(EventType.WARNING, "Screen changed since “${target.name}” was detected; rescanning")
                            continue
                        }
                        ctx.setActivity("Tapping “${target.name}” ×${decision.taps}")
                        val result = ctx.tapBatch(
                            TapBatchRequest(
                                label = target.name,
                                x = detection.tapPoint.x,
                                y = detection.tapPoint.y,
                                count = decision.taps,
                                rate = target.action.rate,
                                preDelayMs = target.action.delayBeforeFirstTapMs,
                                postDelayMs = target.action.delayAfterLastTapMs,
                                reference = detection.geometry,
                            ),
                        )
                        tracker.onBatchFinished(ctx.elapsedMs(), result.completedTaps)
                        if (result.outcome == BatchOutcome.FAILED) {
                            ctx.log(EventType.ERROR, result.message ?: "Tap batch on “${target.name}” failed")
                        }
                        // Repeat-while-visible re-checks visibility right after each chunk (the
                        // chunk already lasted about one scan interval) so tapping continues at
                        // the requested rate; other modes wait for the normal scan interval.
                        nextScanAt[target.id] = ctx.elapsedMs() +
                            if (target.action.mode == DetectionMode.REPEAT_WHILE_VISIBLE) 0 else target.match.scanIntervalMs
                        // The screen has probably changed: rescan instead of acting on this old frame.
                        break
                    }
                    DetectionTracker.Decision.Notify -> {
                        val score = found?.score ?: 0f
                        ctx.log(
                            EventType.NOTIFY,
                            String.format(Locale.US, "“%s” detected (notify only, score %.3f)", target.name, score),
                        )
                    }
                    is DetectionTracker.Decision.TimedOut -> {
                        ctx.increment(StatCounter.DETECTION_TIMEOUT)
                        if (decision.willRetry) {
                            ctx.increment(StatCounter.RETRY)
                            ctx.log(
                                EventType.RETRY,
                                "“${target.name}” not found within ${target.match.timeoutMs} ms; " +
                                    "retrying (attempt ${decision.attempt} of ${decision.maxAttempts})",
                            )
                        } else {
                            ctx.log(
                                EventType.TIMEOUT,
                                "“${target.name}” not found after ${decision.maxAttempts} attempt(s); giving up on it",
                            )
                        }
                    }
                    is DetectionTracker.Decision.Suppressed, DetectionTracker.Decision.None -> Unit
                }
            }
        }
    }

    private fun finalOutcome(trackers: Map<String, DetectionTracker>): TaskOutcome {
        val waitTargets = targets.filter { it.action.mode == DetectionMode.WAIT_AND_CLICK }
        val failed = waitTargets.filter { !trackers.getValue(it.id).succeeded }
        return if (failed.isEmpty()) {
            TaskOutcome(true, "All targets handled")
        } else {
            TaskOutcome(false, "Not found before timeout: ${failed.joinToString { "“${it.name}”" }}")
        }
    }
}
