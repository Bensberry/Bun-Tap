package com.buntap.app.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The real size of the default display (including system bars) in its current rotation.
 * Accessibility gestures and screen captures both use this coordinate space.
 *
 * @param rotation Surface rotation constant (0 = natural, 1 = 90°, 2 = 180°, 3 = 270°).
 */
@Serializable
data class ScreenGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val rotation: Int = 0,
    val densityDpi: Int = 0,
) {
    val isLandscape: Boolean get() = widthPx > heightPx

    fun contains(x: Int, y: Int): Boolean = x in 0 until widthPx && y in 0 until heightPx

    fun sameSize(other: ScreenGeometry): Boolean =
        widthPx == other.widthPx && heightPx == other.heightPx

    fun describe(): String =
        "${widthPx}×${heightPx} ${if (isLandscape) "landscape" else "portrait"}"
}

/** Result of comparing the screen a configuration was created on with the current screen. */
sealed interface GeometryCompatibility {
    /** Same size and orientation: coordinates can be used as-is. */
    data object Same : GeometryCompatibility

    /** Unknown reference (e.g. old data): coordinates are only range-checked. */
    data object Unknown : GeometryCompatibility

    /**
     * Same orientation but a different resolution (e.g. the display-size setting changed).
     * A proportional transformation is offered, but the user must confirm it.
     */
    data class Scalable(val from: ScreenGeometry, val to: ScreenGeometry) : GeometryCompatibility

    /** The orientation changed. Positions in a rotated layout cannot be inferred safely. */
    data class Incompatible(val from: ScreenGeometry, val to: ScreenGeometry) : GeometryCompatibility
}

object GeometryRules {

    fun compare(reference: ScreenGeometry?, current: ScreenGeometry?): GeometryCompatibility {
        if (reference == null || current == null) return GeometryCompatibility.Unknown
        if (reference.sameSize(current)) return GeometryCompatibility.Same
        if (reference.isLandscape != current.isLandscape) {
            return GeometryCompatibility.Incompatible(reference, current)
        }
        return GeometryCompatibility.Scalable(reference, current)
    }

    /** Explains a compatibility problem in plain language, or returns null when there is none. */
    fun explain(compatibility: GeometryCompatibility): String? = when (compatibility) {
        GeometryCompatibility.Same, GeometryCompatibility.Unknown -> null
        is GeometryCompatibility.Scalable ->
            "Positions were picked on a ${compatibility.from.describe()} screen, but the screen is now " +
                "${compatibility.to.describe()}. Scale them proportionally (then check them) or pick them again."
        is GeometryCompatibility.Incompatible ->
            "Positions were picked in ${if (compatibility.from.isLandscape) "landscape" else "portrait"} " +
                "(${compatibility.from.describe()}), but the screen is now ${compatibility.to.describe()}. " +
                "Rotate the device back or pick the positions again."
    }

    /** Proportionally maps a point between two screens with the same orientation. */
    fun scalePoint(x: Int, y: Int, from: ScreenGeometry, to: ScreenGeometry): PixelPoint {
        val nx = (x.toDouble() * to.widthPx / from.widthPx).roundToInt().coerceIn(0, to.widthPx - 1)
        val ny = (y.toDouble() * to.heightPx / from.heightPx).roundToInt().coerceIn(0, to.heightPx - 1)
        return PixelPoint(nx, ny)
    }

    /** True when two aspect ratios are close enough for proportional scaling to be meaningful. */
    fun similarAspect(a: ScreenGeometry, b: ScreenGeometry, tolerance: Double = 0.03): Boolean {
        val ra = a.widthPx.toDouble() / a.heightPx
        val rb = b.widthPx.toDouble() / b.heightPx
        return abs(ra - rb) / ra <= tolerance
    }
}
