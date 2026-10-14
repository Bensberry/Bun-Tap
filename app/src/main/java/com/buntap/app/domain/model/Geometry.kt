package com.buntap.app.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point in screen pixels. */
data class PixelPoint(val x: Int, val y: Int)

/** An axis-aligned rectangle in pixels. [width] and [height] are never negative. */
@Serializable
data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    val area: Long get() = width.toLong() * height.toLong()
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun intersect(other: PixelRect): PixelRect? {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        return if (r > l && b > t) PixelRect(l, t, r - l, b - t) else null
    }

    /** Intersection-over-union, 0.0 when the rectangles do not overlap. */
    fun iou(other: PixelRect): Double {
        val inter = intersect(other)?.area ?: return 0.0
        val union = area + other.area - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Scales coordinates, e.g. to convert from a downscaled frame back to screen pixels. */
    fun scaled(scaleX: Double, scaleY: Double): PixelRect = PixelRect(
        left = (left * scaleX).roundToInt(),
        top = (top * scaleY).roundToInt(),
        width = (width * scaleX).roundToInt(),
        height = (height * scaleY).roundToInt(),
    )

    override fun toString(): String = "[$left,$top ${width}×$height]"
}

/**
 * A user-defined screen region (e.g. a search region for a visual target), in screen pixels.
 * Stored as left/top/right/bottom because that is how users usually think about regions.
 */
@Serializable
data class RegionRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun toPixelRect(): PixelRect = PixelRect(left, top, width, height)
    fun isValid(): Boolean = right > left && bottom > top && left >= 0 && top >= 0
}
