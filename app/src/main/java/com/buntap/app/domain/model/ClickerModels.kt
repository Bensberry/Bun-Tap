package com.buntap.app.domain.model

import kotlinx.serialization.Serializable

/**
 * One position the standard auto-clicker taps.
 *
 * [rateOverride] and [countOverride] are only used when the clicker runs in
 * [SettingsMode.PER_POINT]; delays, timeout and [repeat] always apply.
 */
@Serializable
data class TapPoint(
    val id: String,
    val name: String,
    val x: Int,
    val y: Int,
    val enabled: Boolean = true,
    /** Point-specific rate (per-point mode only). Null = use the global rate. */
    val rateOverride: TapRate? = null,
    /** Point-specific taps per visit (per-point mode only). Null = use the global count. */
    val countOverride: Int? = null,
    /** Wait before this point's taps start. */
    val preDelayMs: Long = 0,
    /** Wait after this point's taps finish. */
    val postDelayMs: Long = 0,
    /** Maximum time allowed for this point's taps; when exceeded the clicker moves on. */
    val timeoutMs: Long? = null,
    /** How many times this point's tap batch is repeated within one pass. */
    val repeat: Int = 1,
)

/** Whether every point uses the global rate/count, or points may override them. */
@Serializable
enum class SettingsMode { GLOBAL, PER_POINT }

/** How often the whole sequence of points is executed. */
@Serializable
enum class RepeatMode {
    /** Run the sequence once, then stop. */
    ONE_PASS,

    /** Run the sequence [ClickerConfig.passes] times. */
    FIXED_PASSES,

    /** Repeat until stopped by the user or a limit. Requires explicit confirmation. */
    UNTIL_STOPPED,
}

/** Complete configuration of the standard auto-clicker. Persisted as JSON. */
@Serializable
data class ClickerConfig(
    val points: List<TapPoint> = emptyList(),
    val settingsMode: SettingsMode = SettingsMode.GLOBAL,
    val globalRate: TapRate = TapRate.DEFAULT,
    /** Taps per point per visit (global mode, and the fallback in per-point mode). */
    val tapsPerPoint: Int = 10,
    val repeatMode: RepeatMode = RepeatMode.ONE_PASS,
    val passes: Int = 2,
    /** Stop after this many successful taps in total (across all points). Null = no extra limit. */
    val sessionTapLimit: Int? = null,
    /** Stop after this much active (unpaused) time. Null = only the global safety limit applies. */
    val maxDurationMs: Long? = null,
    /** Countdown before the first tap so you can switch to the target app. */
    val startDelayMs: Long = 3_000,
    /** The screen the points were picked on. */
    val reference: ScreenGeometry? = null,
)
