package com.buntap.app.vision

import com.buntap.app.domain.model.DetectionMode
import com.buntap.app.domain.model.VisualTarget
import kotlin.math.ceil

/**
 * Tracks one target's visibility across successive scans and decides when a tap batch (or a
 * notification) should be triggered. It is a pure state machine, so it is easy to test.
 *
 * Key guarantees:
 * - A tap batch is triggered at most once per decision; scanning while a batch runs does not
 *   restart it (the visual task only scans between batches).
 * - Once-per-appearance re-arms only after the target disappears (confirmed by
 *   [Config.missesToDisappear] consecutive misses) or, if disappearance is not required,
 *   after the cooldown.
 * - Unknown observations (blank frame, ambiguous match) never count as a disappearance.
 */
class DetectionTracker(private val config: Config, startMs: Long) {

    data class Config(
        val mode: DetectionMode,
        val minVisibleMs: Long = 0,
        val cooldownMs: Long = 0,
        val requireDisappearance: Boolean = true,
        val tapsPerTrigger: Int = 1,
        val maxTapsPerAppearance: Int = 50,
        val maxTotalTaps: Int? = null,
        /** Repeat-while-visible: taps per batch before the next visibility check. */
        val repeatChunk: Int = 1,
        /** Wait-and-click: how long to wait before a timeout. Null = forever. */
        val timeoutMs: Long? = null,
        /** Wait-and-click: number of extra waiting periods after a timeout. */
        val retries: Int = 0,
        /**
         * Consecutive "not found" scans before the target counts as gone (re-arming
         * once-per-appearance). One scan keeps re-taps responsive; the cooldown still prevents
         * duplicate taps from flicker.
         */
        val missesToDisappear: Int = 1,
    ) {
        companion object {
            fun from(target: VisualTarget): Config {
                val a = target.action
                val m = target.match
                val chunk = ceil(m.scanIntervalMs.toDouble() / a.rate.intervalMs).toInt()
                    .coerceIn(1, a.maxTapsPerAppearance.coerceAtLeast(1))
                return Config(
                    mode = a.mode,
                    minVisibleMs = m.minVisibleMs,
                    cooldownMs = a.cooldownMs,
                    requireDisappearance = m.requireDisappearance,
                    tapsPerTrigger = a.tapsPerTrigger,
                    maxTapsPerAppearance = a.maxTapsPerAppearance,
                    maxTotalTaps = a.maxTotalTaps,
                    repeatChunk = chunk,
                    timeoutMs = if (a.mode == DetectionMode.WAIT_AND_CLICK) m.timeoutMs else null,
                    retries = m.retryCount,
                )
            }
        }
    }

    sealed interface Decision {
        data object None : Decision
        data class Tap(val taps: Int) : Decision
        data object Notify : Decision
        data class Suppressed(val reason: String) : Decision
        data class TimedOut(val willRetry: Boolean, val attempt: Int, val maxAttempts: Int) : Decision
    }

    enum class Change { APPEARED, DISAPPEARED }

    data class Observation(val decision: Decision, val change: Change?)

    private var visibleSinceMs: Long? = null
    private var misses = 0
    private var armed = true
    private var lastTriggerEndMs: Long? = null
    private var tapsThisAppearance = 0
    private var totalTaps = 0
    private var waitStartMs = startMs
    private var retriesUsed = 0

    var finished: Boolean = false
        private set

    /** For wait-and-click: true once the target was handled. */
    var succeeded: Boolean = false
        private set

    val isVisible: Boolean get() = visibleSinceMs != null
    val totalTapsSoFar: Int get() = totalTaps

    /** A scan that could not determine visibility (blank frame, ambiguous match). */
    fun observeUnknown(nowMs: Long): Observation {
        if (finished) return Observation(Decision.None, null)
        return Observation(checkWaitTimeout(nowMs) ?: Decision.None, null)
    }

    fun observe(nowMs: Long, present: Boolean): Observation {
        if (finished) return Observation(Decision.None, null)
        if (!present) {
            misses++
            var change: Change? = null
            if (visibleSinceMs != null && misses >= config.missesToDisappear) {
                visibleSinceMs = null
                tapsThisAppearance = 0
                armed = true
                change = Change.DISAPPEARED
            }
            return Observation(checkWaitTimeout(nowMs) ?: Decision.None, change)
        }

        misses = 0
        var change: Change? = null
        if (visibleSinceMs == null) {
            visibleSinceMs = nowMs
            change = Change.APPEARED
        }
        val visibleFor = nowMs - (visibleSinceMs ?: nowMs)
        if (visibleFor < config.minVisibleMs) {
            val timeout = checkWaitTimeout(nowMs)
            return Observation(
                timeout ?: Decision.Suppressed("visible for $visibleFor ms; needs ${config.minVisibleMs} ms"),
                change,
            )
        }
        return Observation(decide(nowMs), change)
    }

    /** Must be called after a triggered tap batch finishes. */
    fun onBatchFinished(nowMs: Long, tapsCompleted: Int) {
        tapsThisAppearance += tapsCompleted
        totalTaps += tapsCompleted
        lastTriggerEndMs = nowMs
        if (config.mode == DetectionMode.WAIT_AND_CLICK) {
            finished = true
            succeeded = true
        }
        val max = config.maxTotalTaps
        if (max != null && totalTaps >= max) finished = true
    }

    private fun cooling(nowMs: Long): Boolean =
        lastTriggerEndMs?.let { nowMs - it < config.cooldownMs } ?: false

    private fun remainingTotal(): Int = config.maxTotalTaps?.let { it - totalTaps } ?: Int.MAX_VALUE

    private fun decide(nowMs: Long): Decision {
        if (remainingTotal() <= 0) {
            finished = true
            return Decision.Suppressed("session tap limit for this target reached")
        }
        return when (config.mode) {
            DetectionMode.ONCE_PER_APPEARANCE -> {
                if (!armed) {
                    if (!config.requireDisappearance && !cooling(nowMs)) {
                        armed = true
                    } else {
                        return Decision.Suppressed(
                            if (config.requireDisappearance) "already triggered; waiting for the target to disappear"
                            else "cooldown",
                        )
                    }
                }
                if (cooling(nowMs)) return Decision.Suppressed("cooldown")
                armed = false
                Decision.Tap(minOf(config.tapsPerTrigger, remainingTotal()))
            }

            DetectionMode.REPEAT_WHILE_VISIBLE -> {
                if (tapsThisAppearance >= config.maxTapsPerAppearance) {
                    if (!config.requireDisappearance && !cooling(nowMs)) {
                        tapsThisAppearance = 0
                    } else {
                        return Decision.Suppressed("maximum taps for this appearance reached")
                    }
                }
                // The cooldown separates appearances; chunks within one appearance continue directly.
                if (tapsThisAppearance == 0 && cooling(nowMs)) return Decision.Suppressed("cooldown")
                val taps = minOf(
                    config.repeatChunk,
                    config.maxTapsPerAppearance - tapsThisAppearance,
                    remainingTotal(),
                )
                Decision.Tap(taps.coerceAtLeast(1))
            }

            DetectionMode.WAIT_AND_CLICK -> {
                if (cooling(nowMs)) return Decision.Suppressed("cooldown")
                Decision.Tap(minOf(config.tapsPerTrigger, remainingTotal()))
            }

            DetectionMode.NOTIFY_ONLY -> {
                if (!armed) {
                    if (!config.requireDisappearance && !cooling(nowMs)) {
                        armed = true
                    } else {
                        return Decision.Suppressed("already reported; waiting for the target to disappear")
                    }
                }
                armed = false
                lastTriggerEndMs = nowMs
                Decision.Notify
            }
        }
    }

    private fun checkWaitTimeout(nowMs: Long): Decision? {
        val timeout = config.timeoutMs ?: return null
        if (config.mode != DetectionMode.WAIT_AND_CLICK || finished) return null
        if (nowMs - waitStartMs < timeout) return null
        val maxAttempts = config.retries + 1
        return if (retriesUsed < config.retries) {
            retriesUsed++
            waitStartMs = nowMs
            Decision.TimedOut(willRetry = true, attempt = retriesUsed + 1, maxAttempts = maxAttempts)
        } else {
            finished = true
            succeeded = false
            Decision.TimedOut(willRetry = false, attempt = retriesUsed + 1, maxAttempts = maxAttempts)
        }
    }
}
