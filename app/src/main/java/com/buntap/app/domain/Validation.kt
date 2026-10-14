package com.buntap.app.domain

import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TapRate
import java.util.Locale

/** Result of parsing/validating user input. */
sealed interface Validated<out T> {
    data class Valid<T>(val value: T) : Validated<T>
    data class Invalid(val message: String) : Validated<Nothing>
}

val <T> Validated<T>.valueOrNull: T?
    get() = (this as? Validated.Valid<T>)?.value

val Validated<*>.errorOrNull: String?
    get() = (this as? Validated.Invalid)?.message

/** Number parsing that accepts both "." and "," as decimal separators. */
object NumberInput {

    fun parseDouble(text: String): Double? {
        val cleaned = text.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    fun parseLong(text: String): Long? = text.trim().toLongOrNull()

    fun parseInt(text: String): Int? = text.trim().toIntOrNull()

    /** Validates an integer in [min]..[max]. */
    fun int(text: String, min: Int, max: Int, label: String): Validated<Int> {
        if (text.isBlank()) return Validated.Invalid("Enter $label")
        val v = parseInt(text) ?: return Validated.Invalid("$label must be a whole number")
        if (v < min) return Validated.Invalid("$label must be at least $min")
        if (v > max) return Validated.Invalid("$label must be at most $max")
        return Validated.Valid(v)
    }

    /** Validates a duration in milliseconds in [min]..[max]. */
    fun millis(text: String, min: Long, max: Long, label: String): Validated<Long> {
        if (text.isBlank()) return Validated.Invalid("Enter $label")
        val v = parseLong(text) ?: return Validated.Invalid("$label must be a whole number of milliseconds")
        if (v < min) return Validated.Invalid("$label must be at least $min ms")
        if (v > max) return Validated.Invalid("$label must be at most $max ms")
        return Validated.Valid(v)
    }
}

/** Validation and formatting for tapping rates (TPS ⇄ milliseconds). */
object RateValidator {

    /** Parses a TPS value typed by the user and checks it against [limits]. */
    fun parseTps(text: String, limits: RateLimits): Validated<TapRate> {
        if (text.isBlank()) return Validated.Invalid("Enter taps per second")
        val tps = NumberInput.parseDouble(text) ?: return Validated.Invalid("Not a valid number")
        if (tps <= 0.0) return Validated.Invalid("TPS must be greater than zero")
        val rate = TapRate.fromTps(tps)
        return check(rate, limits)?.let { Validated.Invalid(it) } ?: Validated.Valid(rate)
    }

    /** Parses an interval in milliseconds typed by the user and checks it against [limits]. */
    fun parseIntervalMs(text: String, limits: RateLimits): Validated<TapRate> {
        if (text.isBlank()) return Validated.Invalid("Enter an interval in milliseconds")
        val ms = NumberInput.parseDouble(text) ?: return Validated.Invalid("Not a valid number")
        if (ms <= 0.0) return Validated.Invalid("Interval must be greater than zero")
        val rate = TapRate.fromIntervalMs(ms)
        return check(rate, limits)?.let { Validated.Invalid(it) } ?: Validated.Valid(rate)
    }

    /** Returns a user-facing problem with [rate], or null when it is allowed. */
    fun check(rate: TapRate, limits: RateLimits): String? {
        // Compare in microseconds to avoid floating-point edge cases at the limits.
        val minIntervalUs = Math.round(limits.minIntervalMs * 1000.0)
        val maxIntervalUs = Math.round(limits.maxIntervalMs * 1000.0)
        if (rate.intervalMicros < minIntervalUs) {
            val pressBoundMs = limits.pressDurationMs + RateLimits.GESTURE_MARGIN_MS
            val tpsBoundMs = 1000.0 / limits.maxTps
            return if (pressBoundMs > tpsBoundMs) {
                "Interval must be at least $pressBoundMs ms because each tap is held for " +
                    "${limits.pressDurationMs} ms (change the press duration in Settings)"
            } else {
                "Maximum is ${formatTps(limits.maxTps)} TPS (${formatMs(1000.0 / limits.maxTps)} ms). " +
                    "You can change the limit in Settings → Safety limits"
            }
        }
        if (rate.intervalMicros > maxIntervalUs) {
            return "Minimum is ${formatTps(limits.minTps)} TPS (one tap every ${formatMs(limits.maxIntervalMs)} ms)"
        }
        return null
    }

    /** Formats TPS with at most two decimals and no trailing zeros ("5", "3.33", "0.5"). */
    fun formatTps(tps: Double): String = trimZeros(String.format(Locale.US, "%.2f", tps))

    /** Formats a millisecond interval with at most three decimals ("200", "333.333"). */
    fun formatMs(ms: Double): String = trimZeros(String.format(Locale.US, "%.3f", ms))

    private fun trimZeros(s: String): String =
        if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/** Coordinate checks shared by the clicker and routines. */
object CoordinateValidator {

    fun check(x: Int, y: Int, geometry: ScreenGeometry?): String? {
        if (x < 0 || y < 0) return "Coordinates cannot be negative ($x, $y)"
        if (geometry != null && !geometry.contains(x, y)) {
            return "($x, $y) is outside the screen (${geometry.widthPx}×${geometry.heightPx})"
        }
        return null
    }
}
