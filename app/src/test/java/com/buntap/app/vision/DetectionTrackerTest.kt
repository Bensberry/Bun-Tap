package com.buntap.app.vision

import com.buntap.app.domain.model.DetectionMode
import com.buntap.app.vision.DetectionTracker.Change
import com.buntap.app.vision.DetectionTracker.Config
import com.buntap.app.vision.DetectionTracker.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTrackerTest {

    private fun tracker(config: Config) = DetectionTracker(config, startMs = 0)

    @Test
    fun oncePerAppearanceTriggersOnlyOnceWhileVisible() {
        val t = tracker(Config(DetectionMode.ONCE_PER_APPEARANCE, tapsPerTrigger = 3))
        val first = t.observe(0, true)
        assertEquals(Change.APPEARED, first.change)
        assertEquals(Decision.Tap(3), first.decision)
        t.onBatchFinished(100, 3)
        repeat(10) { i ->
            val o = t.observe(200L + i * 100, true)
            assertTrue("duplicate trigger suppressed", o.decision is Decision.Suppressed)
        }
    }

    @Test
    fun reArmsAfterConfirmedDisappearance() {
        val t = tracker(Config(DetectionMode.ONCE_PER_APPEARANCE, missesToDisappear = 2))
        assertEquals(Decision.Tap(1), t.observe(0, true).decision)
        t.onBatchFinished(10, 1)
        // A single miss (flicker) does not count as disappearance.
        assertEquals(null, t.observe(100, false).change)
        assertTrue(t.observe(200, true).decision is Decision.Suppressed)
        assertEquals(null, t.observe(300, false).change)
        assertEquals(Change.DISAPPEARED, t.observe(400, false).change)
        val again = t.observe(500, true)
        assertEquals(Change.APPEARED, again.change)
        assertEquals(Decision.Tap(1), again.decision)
    }

    @Test
    fun unknownObservationsDoNotCountAsDisappearance() {
        val t = tracker(Config(DetectionMode.ONCE_PER_APPEARANCE))
        t.observe(0, true)
        t.onBatchFinished(10, 1)
        repeat(5) { t.observeUnknown(100L + it * 100) }
        assertTrue(t.isVisible)
        assertTrue(t.observe(700, true).decision is Decision.Suppressed)
    }

    @Test
    fun cooldownReArmsWhenDisappearanceNotRequired() {
        val t = tracker(Config(DetectionMode.ONCE_PER_APPEARANCE, requireDisappearance = false, cooldownMs = 1_000))
        assertEquals(Decision.Tap(1), t.observe(0, true).decision)
        t.onBatchFinished(100, 1)
        assertEquals(Decision.Suppressed("cooldown"), t.observe(600, true).decision)
        assertEquals(Decision.Tap(1), t.observe(1_100, true).decision)
    }

    @Test
    fun cooldownAlsoAppliesAfterReappearance() {
        val t = tracker(Config(DetectionMode.ONCE_PER_APPEARANCE, cooldownMs = 2_000, missesToDisappear = 1))
        t.observe(0, true)
        t.onBatchFinished(100, 1)
        t.observe(200, false)
        assertTrue(t.observe(300, true).decision is Decision.Suppressed)
        assertEquals(Decision.Tap(1), t.observe(2_200, true).decision)
    }

    @Test
    fun minimumVisibleTimeFiltersFlicker() {
        val t = tracker(Config(DetectionMode.ONCE_PER_APPEARANCE, minVisibleMs = 500))
        assertTrue(t.observe(0, true).decision is Decision.Suppressed)
        assertTrue(t.observe(300, true).decision is Decision.Suppressed)
        assertEquals(Decision.Tap(1), t.observe(500, true).decision)
    }

    @Test
    fun repeatWhileVisibleChunksUntilMaxPerAppearance() {
        val t = tracker(Config(DetectionMode.REPEAT_WHILE_VISIBLE, repeatChunk = 4, maxTapsPerAppearance = 10))
        assertEquals(Decision.Tap(4), t.observe(0, true).decision)
        t.onBatchFinished(400, 4)
        assertEquals(Decision.Tap(4), t.observe(500, true).decision)
        t.onBatchFinished(900, 4)
        assertEquals(Decision.Tap(2), t.observe(1_000, true).decision)
        t.onBatchFinished(1_200, 2)
        assertTrue(t.observe(1_300, true).decision is Decision.Suppressed)
        // Disappear and reappear: a new appearance gets a fresh budget.
        t.observe(1_400, false)
        t.observe(1_500, false)
        assertEquals(Decision.Tap(4), t.observe(1_600, true).decision)
    }

    @Test
    fun repeatStopsWhenTargetDisappears() {
        val t = tracker(Config(DetectionMode.REPEAT_WHILE_VISIBLE, repeatChunk = 2, missesToDisappear = 1))
        assertEquals(Decision.Tap(2), t.observe(0, true).decision)
        t.onBatchFinished(200, 2)
        val gone = t.observe(300, false)
        assertEquals(Change.DISAPPEARED, gone.change)
        assertEquals(Decision.None, gone.decision)
    }

    @Test
    fun maxTotalTapsFinishesTracker() {
        val t = tracker(Config(DetectionMode.REPEAT_WHILE_VISIBLE, repeatChunk = 5, maxTotalTaps = 7, maxTapsPerAppearance = 100))
        assertEquals(Decision.Tap(5), t.observe(0, true).decision)
        t.onBatchFinished(100, 5)
        assertEquals(Decision.Tap(2), t.observe(200, true).decision)
        t.onBatchFinished(300, 2)
        assertTrue(t.finished)
        assertEquals(7, t.totalTapsSoFar)
    }

    @Test
    fun waitAndClickFinishesAfterOneBatch() {
        val t = tracker(Config(DetectionMode.WAIT_AND_CLICK, tapsPerTrigger = 2, timeoutMs = 5_000))
        assertEquals(Decision.None, t.observe(1_000, false).decision)
        assertEquals(Decision.Tap(2), t.observe(2_000, true).decision)
        t.onBatchFinished(2_500, 2)
        assertTrue(t.finished)
        assertTrue(t.succeeded)
        assertEquals(Decision.None, t.observe(3_000, true).decision)
    }

    @Test
    fun waitAndClickTimesOutWithRetries() {
        val t = tracker(Config(DetectionMode.WAIT_AND_CLICK, timeoutMs = 1_000, retries = 2))
        assertEquals(Decision.None, t.observe(500, false).decision)
        assertEquals(Decision.TimedOut(willRetry = true, attempt = 2, maxAttempts = 3), t.observe(1_000, false).decision)
        assertEquals(Decision.TimedOut(willRetry = true, attempt = 3, maxAttempts = 3), t.observe(2_000, false).decision)
        assertEquals(Decision.TimedOut(willRetry = false, attempt = 3, maxAttempts = 3), t.observe(3_000, false).decision)
        assertTrue(t.finished)
        assertFalse(t.succeeded)
    }

    @Test
    fun notifyOnlyReportsOncePerAppearance() {
        val t = tracker(Config(DetectionMode.NOTIFY_ONLY, missesToDisappear = 1))
        assertEquals(Decision.Notify, t.observe(0, true).decision)
        assertTrue(t.observe(100, true).decision is Decision.Suppressed)
        t.observe(200, false)
        assertEquals(Decision.Notify, t.observe(300, true).decision)
    }
}
