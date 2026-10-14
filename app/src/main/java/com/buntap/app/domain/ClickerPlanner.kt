package com.buntap.app.domain

import com.buntap.app.domain.model.ClickerConfig
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.RepeatMode
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.SettingsMode
import com.buntap.app.domain.model.TapPoint
import com.buntap.app.domain.model.TapRate

/** A tap point with the global/per-point settings already resolved. */
data class EffectivePoint(
    val point: TapPoint,
    val rate: TapRate,
    /** Taps per visit of this point. */
    val count: Int,
    /** Visits of this point per pass. */
    val repeat: Int,
) {
    val tapsPerPass: Long get() = count.toLong() * repeat.toLong()
}

/** The planned amount of work, explained in plain language for the UI. */
data class ClickerPlan(
    val points: List<EffectivePoint>,
    val tapsPerPass: Long,
    /** Number of passes, or null when repeating until stopped. */
    val passes: Int?,
    /** Planned successful taps, or null when unlimited. */
    val plannedTaps: Int?,
    val limitedBySessionLimit: Boolean,
    val explanation: String,
)

/** Pure functions that turn a [ClickerConfig] into concrete work and validate it. */
object ClickerPlanner {

    const val MAX_TAPS_PER_POINT = 1_000_000
    const val MAX_PASSES = 100_000
    const val MAX_REPEAT = 1_000
    const val MAX_DELAY_MS = 600_000L
    const val MIN_TIMEOUT_MS = 100L
    const val MAX_TIMEOUT_MS = 3_600_000L
    const val MAX_START_DELAY_MS = 60_000L

    fun effectivePoints(config: ClickerConfig): List<EffectivePoint> =
        config.points.filter { it.enabled }.map { p ->
            val perPoint = config.settingsMode == SettingsMode.PER_POINT
            EffectivePoint(
                point = p,
                rate = if (perPoint) p.rateOverride ?: config.globalRate else config.globalRate,
                count = if (perPoint) p.countOverride ?: config.tapsPerPoint else config.tapsPerPoint,
                repeat = p.repeat.coerceAtLeast(1),
            )
        }

    fun plan(config: ClickerConfig): ClickerPlan {
        val points = effectivePoints(config)
        val perPass = points.sumOf { it.tapsPerPass }
        val passes = when (config.repeatMode) {
            RepeatMode.ONE_PASS -> 1
            RepeatMode.FIXED_PASSES -> config.passes.coerceAtLeast(1)
            RepeatMode.UNTIL_STOPPED -> null
        }
        val sequenceTotal: Long? = passes?.let { perPass * it }
        val limit = config.sessionTapLimit
        val planned: Long? = when {
            sequenceTotal == null -> limit?.toLong()
            limit == null -> sequenceTotal
            else -> minOf(sequenceTotal, limit.toLong())
        }
        val limited = limit != null && (sequenceTotal == null || limit < sequenceTotal)
        return ClickerPlan(
            points = points,
            tapsPerPass = perPass,
            passes = passes,
            plannedTaps = planned?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            limitedBySessionLimit = limited,
            explanation = explain(config, points, perPass, passes, sequenceTotal, limit),
        )
    }

    private fun explain(
        config: ClickerConfig,
        points: List<EffectivePoint>,
        perPass: Long,
        passes: Int?,
        sequenceTotal: Long?,
        limit: Int?,
    ): String {
        if (points.isEmpty()) return "No enabled tap points yet."
        val sb = StringBuilder()
        val uniform = points.all { it.count == points.first().count && it.repeat == 1 }
        if (uniform) {
            val n = points.size
            sb.append("$n point${if (n == 1) "" else "s"} × ${points.first().count} taps = $perPass taps per pass")
        } else {
            sb.append("Per pass: ")
            sb.append(points.joinToString(" + ") { ep ->
                if (ep.repeat > 1) "${ep.point.name} ${ep.count}×${ep.repeat}" else "${ep.point.name} ${ep.count}"
            })
            sb.append(" = $perPass taps")
        }
        when {
            passes == null -> sb.append(". Repeats until you stop it")
            passes == 1 -> sb.append(", one pass → $sequenceTotal planned taps")
            else -> sb.append(" × $passes passes = $sequenceTotal planned taps")
        }
        if (limit != null) {
            if (sequenceTotal == null || limit < sequenceTotal) {
                sb.append("; stops after $limit successful taps (session tap limit)")
            } else {
                sb.append("; session tap limit $limit is not reached")
            }
        }
        if (config.settingsMode == SettingsMode.GLOBAL) {
            sb.append(". The tap count applies to each point separately.")
        } else {
            sb.append(". Each point uses its own count where set.")
        }
        if (passes == null && limit == null) {
            sb.append(" Safety limits from Settings still apply.")
        }
        return sb.toString()
    }

    /** Returns a list of problems that prevent the configuration from running. */
    fun validate(config: ClickerConfig, limits: RateLimits, geometry: ScreenGeometry?): List<String> {
        val errors = mutableListOf<String>()
        val enabled = config.points.filter { it.enabled }
        if (enabled.isEmpty()) errors += "Add at least one enabled tap point."
        RateValidator.check(config.globalRate, limits)?.let { errors += "Global rate: $it" }
        if (config.tapsPerPoint !in 1..MAX_TAPS_PER_POINT) {
            errors += "Taps per point must be between 1 and $MAX_TAPS_PER_POINT."
        }
        if (config.repeatMode == RepeatMode.FIXED_PASSES && config.passes !in 1..MAX_PASSES) {
            errors += "Passes must be between 1 and $MAX_PASSES."
        }
        config.sessionTapLimit?.let { if (it < 1) errors += "Session tap limit must be at least 1." }
        config.maxDurationMs?.let { if (it < 1_000) errors += "Maximum duration must be at least 1 second." }
        if (config.startDelayMs !in 0..MAX_START_DELAY_MS) {
            errors += "Start delay must be between 0 and ${MAX_START_DELAY_MS / 1000} seconds."
        }
        for (p in enabled) {
            CoordinateValidator.check(p.x, p.y, geometry)?.let { errors += "${p.name}: $it" }
            if (config.settingsMode == SettingsMode.PER_POINT) {
                p.rateOverride?.let { r -> RateValidator.check(r, limits)?.let { errors += "${p.name}: $it" } }
                p.countOverride?.let { c ->
                    if (c !in 1..MAX_TAPS_PER_POINT) errors += "${p.name}: tap count must be between 1 and $MAX_TAPS_PER_POINT."
                }
            }
            if (p.preDelayMs !in 0..MAX_DELAY_MS || p.postDelayMs !in 0..MAX_DELAY_MS) {
                errors += "${p.name}: delays must be between 0 and ${MAX_DELAY_MS / 1000} seconds."
            }
            p.timeoutMs?.let { t ->
                if (t !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) errors += "${p.name}: timeout must be between $MIN_TIMEOUT_MS ms and 1 hour."
            }
            if (p.repeat !in 1..MAX_REPEAT) errors += "${p.name}: repeat must be between 1 and $MAX_REPEAT."
        }
        return errors
    }
}
