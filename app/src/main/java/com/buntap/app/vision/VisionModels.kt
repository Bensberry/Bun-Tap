package com.buntap.app.vision

import com.buntap.app.domain.model.PixelPoint
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.VisualTarget

/** One template-matching candidate in screen pixels. */
data class MatchCandidate(val rect: PixelRect, val score: Float)

/** A confirmed detection of a target on a specific frame. */
data class Detection(
    val targetId: String,
    /** Matched rectangle in screen pixels. */
    val rect: PixelRect,
    val score: Float,
    /** Where the target's configured anchor/offset puts the tap, in screen pixels. */
    val tapPoint: PixelPoint,
    /** Screen geometry of the frame the detection came from. */
    val geometry: ScreenGeometry?,
    /** Number of candidates above the threshold before suppression. */
    val candidateCount: Int,
)

/** Result of searching one frame for one target. */
sealed interface DetectionOutcome {
    data class Found(val detection: Detection) : DetectionOutcome

    /** Not found; [bestScore] is the best (below-threshold) score, for diagnostics. */
    data class NotFound(val bestScore: Float?) : DetectionOutcome

    /** Several similar matches: BunTap does not guess and skips the tap. */
    data class Ambiguous(val candidates: List<MatchCandidate>, val reason: String) : DetectionOutcome

    /** The search could not run (no frame, blank frame, missing image, OpenCV unavailable…). */
    data class Unavailable(val reason: String) : DetectionOutcome
}

/** Outcomes for several targets searched on the same frame. */
data class DetectionBatch(
    val outcomes: Map<String, DetectionOutcome>,
    val frameGeometry: ScreenGeometry?,
    /** Set when the whole frame was unusable. */
    val frameProblem: String? = null,
    val processingMs: Long = 0,
)

/** Searches the current screen for targets. Implemented with OpenCV; faked in tests. */
interface TargetDetector {
    suspend fun detect(targets: List<VisualTarget>): DetectionBatch
}
