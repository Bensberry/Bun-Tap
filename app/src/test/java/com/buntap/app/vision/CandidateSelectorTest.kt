package com.buntap.app.vision

import com.buntap.app.domain.model.PixelPoint
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.TapAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateSelectorTest {

    private fun c(x: Int, y: Int, score: Float, w: Int = 40, h: Int = 20) = MatchCandidate(PixelRect(x, y, w, h), score)

    @Test
    fun noneWhenBelowThreshold() {
        val s = CandidateSelector.select(listOf(c(0, 0, 0.7f)), threshold = 0.85f, allowMultiple = false)
        assertEquals(CandidateSelector.Selection.None(0.7f), s)
    }

    @Test
    fun singleMatchSelected() {
        val s = CandidateSelector.select(listOf(c(10, 10, 0.95f), c(300, 300, 0.5f)), 0.85f, false)
        assertTrue(s is CandidateSelector.Selection.Best)
        assertEquals(PixelRect(10, 10, 40, 20), (s as CandidateSelector.Selection.Best).candidate.rect)
    }

    @Test
    fun overlappingCandidatesAreMergedByNms() {
        val s = CandidateSelector.select(listOf(c(10, 10, 0.95f), c(12, 11, 0.94f)), 0.85f, false)
        assertTrue("overlap is the same object, not ambiguity", s is CandidateSelector.Selection.Best)
    }

    @Test
    fun twoSimilarDistantMatchesAreAmbiguous() {
        val s = CandidateSelector.select(listOf(c(10, 10, 0.95f), c(500, 900, 0.94f)), 0.85f, false)
        assertTrue(s is CandidateSelector.Selection.Ambiguous)
    }

    @Test
    fun clearlyBetterMatchIsNotAmbiguous() {
        val s = CandidateSelector.select(listOf(c(10, 10, 0.97f), c(500, 900, 0.88f)), 0.85f, false)
        assertTrue(s is CandidateSelector.Selection.Best)
    }

    @Test
    fun allowMultiplePicksBest() {
        val s = CandidateSelector.select(listOf(c(500, 900, 0.94f), c(10, 10, 0.95f)), 0.85f, true)
        assertEquals(PixelRect(10, 10, 40, 20), (s as CandidateSelector.Selection.Best).candidate.rect)
    }

    @Test
    fun nanScoresIgnored() {
        val s = CandidateSelector.select(listOf(c(0, 0, Float.NaN)), 0.85f, false)
        assertEquals(CandidateSelector.Selection.None(null), s)
    }

    @Test
    fun nmsKeepsHighestScore() {
        val kept = NonMaxSuppression.filter(listOf(c(0, 0, 0.8f), c(2, 2, 0.9f), c(200, 200, 0.85f)))
        assertEquals(listOf(0.9f, 0.85f), kept.map { it.score })
    }

    @Test
    fun tapPositionCenterAnchorAndOffset() {
        val rect = PixelRect(100, 200, 41, 21)
        assertEquals(PixelPoint(120, 210), TapPositionResolver.resolve(rect, TapAnchor.CENTER, 0, 0))
        assertEquals(PixelPoint(100, 200), TapPositionResolver.resolve(rect, TapAnchor(0f, 0f), 0, 0))
        assertEquals(PixelPoint(140, 220), TapPositionResolver.resolve(rect, TapAnchor(1f, 1f), 0, 0))
        assertEquals(PixelPoint(125, 205), TapPositionResolver.resolve(rect, TapAnchor.CENTER, 5, -5))
    }

    @Test
    fun rectIou() {
        val a = PixelRect(0, 0, 10, 10)
        assertEquals(1.0, a.iou(a), 1e-9)
        assertEquals(0.0, a.iou(PixelRect(20, 20, 10, 10)), 1e-9)
        assertEquals(25.0 / 175.0, a.iou(PixelRect(5, 5, 10, 10)), 1e-9)
    }
}
