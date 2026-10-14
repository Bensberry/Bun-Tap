package com.buntap.app.vision

import com.buntap.app.domain.model.PixelPoint
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.TapAnchor
import java.util.Locale
import kotlin.math.roundToInt

/** Greedy non-maximum suppression: keeps the best candidate among overlapping ones. */
object NonMaxSuppression {
    fun filter(candidates: List<MatchCandidate>, iouThreshold: Double = 0.3): List<MatchCandidate> {
        val sorted = candidates.sortedWith(compareByDescending<MatchCandidate> { it.score }
            .thenBy { it.rect.top }.thenBy { it.rect.left })
        val kept = ArrayList<MatchCandidate>()
        for (c in sorted) {
            if (kept.none { it.rect.iou(c.rect) > iouThreshold }) kept.add(c)
        }
        return kept
    }
}

/**
 * Decides which (if any) candidate is a valid detection. Pure Kotlin so it can be unit tested
 * without OpenCV.
 */
object CandidateSelector {

    /** Two matches whose scores differ by less than this are treated as equally likely. */
    const val AMBIGUITY_MARGIN = 0.02f

    sealed interface Selection {
        data class Best(val candidate: MatchCandidate, val aboveThreshold: Int) : Selection
        data class None(val bestScore: Float?) : Selection
        data class Ambiguous(val candidates: List<MatchCandidate>, val reason: String) : Selection
    }

    fun select(
        candidates: List<MatchCandidate>,
        threshold: Float,
        allowMultiple: Boolean,
    ): Selection {
        val valid = candidates.filter { !it.score.isNaN() }
        val bestScore = valid.maxOfOrNull { it.score }
        val above = valid.filter { it.score >= threshold }
        if (above.isEmpty()) return Selection.None(bestScore)
        val distinct = NonMaxSuppression.filter(above)
        if (distinct.size == 1 || allowMultiple) {
            return Selection.Best(distinct.first(), above.size)
        }
        val first = distinct[0]
        val second = distinct[1]
        return if (first.score - second.score < AMBIGUITY_MARGIN) {
            Selection.Ambiguous(
                distinct,
                String.format(
                    Locale.US,
                    "%d similar matches (best scores %.3f and %.3f at %s and %s); tap skipped",
                    distinct.size, first.score, second.score, first.rect, second.rect,
                ),
            )
        } else {
            Selection.Best(first, above.size)
        }
    }
}

/** Computes where to tap inside a matched rectangle. */
object TapPositionResolver {
    fun resolve(rect: PixelRect, anchor: TapAnchor, offsetX: Int, offsetY: Int): PixelPoint {
        val fx = anchor.fx.coerceIn(0f, 1f)
        val fy = anchor.fy.coerceIn(0f, 1f)
        // Anchor 1.0 means the last pixel, not one past the edge.
        val x = rect.left + (fx * (rect.width - 1).coerceAtLeast(0)).roundToInt() + offsetX
        val y = rect.top + (fy * (rect.height - 1).coerceAtLeast(0)).roundToInt() + offsetY
        return PixelPoint(x, y)
    }
}
