package com.buntap.app.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/**
 * A requested tapping rate.
 *
 * The rate is stored canonically as an interval in **microseconds** so converting between
 * taps-per-second (TPS) and milliseconds never accumulates floating-point drift:
 *
 *     interval (ms) = 1000 / TPS
 *
 * Examples: 1 TPS = 1000 ms, 2 TPS = 500 ms, 5 TPS = 200 ms, 10 TPS = 100 ms, 20 TPS = 50 ms.
 *
 * This is a *requested scheduling interval*. Android does not guarantee that gestures are
 * delivered at exactly this rate; the observed rate is measured separately while running.
 */
@Serializable
data class TapRate(val intervalMicros: Long) {

    init {
        require(intervalMicros > 0) { "Tap interval must be greater than zero" }
    }

    /** Interval between scheduled taps in milliseconds (may be fractional, e.g. 333.333). */
    val intervalMs: Double get() = intervalMicros / 1_000.0

    /** Interval in nanoseconds, used by the monotonic scheduler. */
    val intervalNanos: Long get() = intervalMicros * 1_000L

    /** Taps per second. */
    val tps: Double get() = 1_000_000.0 / intervalMicros

    companion object {
        /** Creates a rate from taps per second. [tps] must be positive and finite. */
        fun fromTps(tps: Double): TapRate {
            require(tps > 0.0 && tps.isFinite()) { "TPS must be a positive number" }
            return TapRate((1_000_000.0 / tps).roundToLong().coerceAtLeast(1L))
        }

        /** Creates a rate from an interval in milliseconds. [ms] must be positive and finite. */
        fun fromIntervalMs(ms: Double): TapRate {
            require(ms > 0.0 && ms.isFinite()) { "Interval must be a positive number" }
            return TapRate((ms * 1_000.0).roundToLong().coerceAtLeast(1L))
        }

        val DEFAULT: TapRate = fromTps(5.0)
    }
}

/**
 * Limits that apply to every requested rate. [maxTps] comes from the user's safety settings.
 * [pressDurationMs] is how long each tap gesture is held; the interval must leave room for it,
 * because dispatching a new gesture cancels one that is still in progress.
 */
data class RateLimits(
    val maxTps: Double = DEFAULT_MAX_TPS,
    val minTps: Double = MIN_TPS,
    val pressDurationMs: Long = DEFAULT_PRESS_DURATION_MS,
) {
    /** Smallest interval allowed by [maxTps] and the gesture press duration. */
    val minIntervalMs: Double
        get() = maxOf(1000.0 / maxTps, (pressDurationMs + GESTURE_MARGIN_MS).toDouble())

    /** Largest interval allowed by [minTps]. */
    val maxIntervalMs: Double get() = 1000.0 / minTps

    /** Highest TPS that is actually permitted after considering the press duration. */
    val effectiveMaxTps: Double get() = 1000.0 / minIntervalMs

    companion object {
        /** Absolute ceiling for the user-configurable maximum. Android rarely sustains more. */
        const val HARD_MAX_TPS = 50.0
        const val DEFAULT_MAX_TPS = 20.0
        /** One tap every 10 seconds. */
        const val MIN_TPS = 0.1
        const val DEFAULT_PRESS_DURATION_MS = 10L
        /** Extra time between gestures so one tap finishes before the next is dispatched. */
        const val GESTURE_MARGIN_MS = 5L
    }
}

/** Common speed presets shown next to the rate editor. */
object RatePresets {
    val tpsValues: List<Double> = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0)
}
