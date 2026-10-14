package com.buntap.app.automation

import com.buntap.app.domain.RateValidator
import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.StopReason
import com.buntap.app.domain.model.TapRate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil

/**
 * The single place where taps are scheduled and dispatched.
 *
 * Scheduling uses fixed-rate deadlines on a monotonic clock: tap *n* is due at
 * `start + n × interval`. The scheduler suspends with `delay` until each deadline (no busy
 * waiting). If gestures take longer than the interval, missed deadlines are skipped and
 * counted instead of being "caught up" in a burst. In addition, two taps are never dispatched
 * closer together than the interval minus a tiny jitter allowance (≤ 5 %, max 2 ms), so a
 * single slow gesture cannot cause the next tap to follow too quickly.
 *
 * The scheduler waits for Android's completion callback before the next tap, because
 * dispatching a new gesture cancels one that is still in progress.
 */
class TapScheduler(
    private val clock: MonotonicClock,
    private val gestures: GestureDispatcher,
    private val stats: StatsTracker,
    private val timer: ActiveTimer,
    private val limits: SafetyLimits,
    private val host: Host,
) {
    /** Callbacks into the engine. */
    interface Host {
        suspend fun checkpoint(): Boolean
        suspend fun sleep(ms: Long)

        /**
         * Final checks right before a gesture. Returns a reason to skip this tap, or null to
         * proceed. Throws [SessionEndException] when a session limit has been reached or the
         * screen no longer matches [reference].
         */
        fun preflight(x: Int, y: Int, reference: ScreenGeometry?): String?
        fun log(type: EventType, message: String)
    }

    private class Progress {
        var completed = 0
        var failed = 0
    }

    /** Start time of the previous tap, so the first tap of the next batch keeps the spacing. */
    private var lastTapNs: Long? = null

    suspend fun run(request: TapBatchRequest): BatchResult {
        val rate = clampToLimits(request.rate)
        stats.update { it.copy(requestedTps = rate.tps) }
        if (request.preDelayMs > 0) host.sleep(request.preDelayMs)

        val progress = Progress()
        val result = if (request.timeoutMs != null) {
            when (val r = timer.withActiveTimeout(request.timeoutMs) { tapLoop(request, rate, progress) }) {
                is Timed.Done -> r.value
                Timed.TimedOut -> BatchResult(
                    BatchOutcome.TIMED_OUT, progress.completed, progress.failed,
                    "Timed out after ${request.timeoutMs} ms (${progress.completed} taps completed)",
                )
            }
        } else {
            tapLoop(request, rate, progress)
        }

        if (request.postDelayMs > 0) host.sleep(request.postDelayMs)
        return result
    }

    private fun clampToLimits(requested: TapRate): TapRate {
        val minIntervalUs = ceil(limits.rateLimits.minIntervalMs * 1000.0).toLong()
        if (requested.intervalMicros >= minIntervalUs) return requested
        val clamped = TapRate(minIntervalUs)
        host.log(
            EventType.WARNING,
            "Requested ${RateValidator.formatTps(requested.tps)} TPS exceeds the allowed maximum; " +
                "using ${RateValidator.formatTps(clamped.tps)} TPS",
        )
        return clamped
    }

    private suspend fun tapLoop(request: TapBatchRequest, rate: TapRate, progress: Progress): BatchResult {
        val intervalNs = rate.intervalNanos
        // Two taps are never closer than this. The small allowance (≤ 5 %, max 2 ms) lets the
        // fixed-rate schedule absorb timer jitter and recover gradually after a slow gesture,
        // without ever sending a catch-up burst.
        val minGapNs = intervalNs - minOf(intervalNs / 20, MAX_JITTER_ALLOWANCE_NS)
        val now0 = clock.nanos()
        var nextDeadline = lastTapNs?.let { maxOf(it + intervalNs, now0) } ?: now0
        var consecutiveFailures = 0
        var skipWarned = false

        while (request.count == null || progress.completed < request.count) {
            // Safe point: honours pause. After a pause, restart the schedule instead of bursting.
            if (host.checkpoint()) {
                nextDeadline = clock.nanos()
                stats.breakContinuity()
            }
            val earliest = lastTapNs?.let { maxOf(nextDeadline, it + minGapNs) } ?: nextDeadline
            val waitNs = earliest - clock.nanos()
            if (waitNs > 0 && timer.delayActive(waitNs)) {
                nextDeadline = clock.nanos()
                stats.breakContinuity()
            }
            currentCoroutineContext().ensureActive()

            val skipReason = host.preflight(request.x, request.y, request.reference)
            if (skipReason != null) {
                stats.update { it.copy(skipped = it.skipped + 1) }
                progress.failed++
                consecutiveFailures++
                if (!skipWarned) {
                    host.log(EventType.WARNING, "Skipped tap on ${request.label}: $skipReason")
                    skipWarned = true
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return BatchResult(
                        BatchOutcome.FAILED, progress.completed, progress.failed,
                        "Taps on ${request.label} were skipped repeatedly: $skipReason",
                    )
                }
                nextDeadline = advance(nextDeadline, intervalNs)
                continue
            }

            stats.update { it.copy(scheduled = it.scheduled + 1) }
            val startNs = clock.nanos()
            lastTapNs = startNs
            val result = gestures.tap(request.x, request.y, limits.pressDurationMs)
            stats.recordGesture(result, clock.nanos())

            when (result) {
                GestureResult.COMPLETED -> {
                    progress.completed++
                    consecutiveFailures = 0
                }
                GestureResult.UNAVAILABLE -> throw SessionEndException(
                    StopReason.ACCESSIBILITY_LOST,
                    "The BunTap accessibility service is not running, so taps cannot be performed",
                )
                else -> {
                    progress.failed++
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        return BatchResult(
                            BatchOutcome.FAILED, progress.completed, progress.failed,
                            "Android reported $MAX_CONSECUTIVE_FAILURES failed gestures in a row " +
                                "(last result: ${result.name.lowercase()}) on ${request.label}",
                        )
                    }
                }
            }
            nextDeadline = advance(nextDeadline, intervalNs)
        }
        return BatchResult(BatchOutcome.COMPLETED, progress.completed, progress.failed)
    }

    /** Moves to the next deadline, skipping (and counting) deadlines that have already passed. */
    private fun advance(deadline: Long, intervalNs: Long): Long {
        var next = deadline + intervalNs
        val behind = clock.nanos() - next
        if (behind > intervalNs) {
            val missed = behind / intervalNs
            next += missed * intervalNs
            stats.update { it.copy(missedDeadlines = it.missedDeadlines + missed.toInt()) }
        }
        return next
    }

    companion object {
        /** A batch fails after this many consecutive failed or skipped taps. */
        const val MAX_CONSECUTIVE_FAILURES = 5

        /** Largest timer-jitter allowance between two taps (see `minGapNs`). */
        const val MAX_JITTER_ALLOWANCE_NS = 2_000_000L
    }
}
