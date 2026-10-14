package com.buntap.app.automation

import com.buntap.app.domain.model.DetectionMode
import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.MatchSettings
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.TapRate
import com.buntap.app.domain.model.TargetActionSettings
import com.buntap.app.testing.EngineHarness
import com.buntap.app.testing.FakeDetector
import com.buntap.app.testing.found
import com.buntap.app.testing.notFound
import com.buntap.app.testing.testTarget
import com.buntap.app.vision.DetectionOutcome
import com.buntap.app.vision.MatchCandidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Detector → tracker → scheduler integration with a scripted detector (no OpenCV). */
@OptIn(ExperimentalCoroutinesApi::class)
class VisualWatchTaskTest {

    @Test
    fun oncePerAppearanceTapsOnceWhileTargetStaysVisible() = runTest {
        val h = EngineHarness(this)
        val target = testTarget("A", DetectionMode.ONCE_PER_APPEARANCE)
        // Visible from 1 s to 4 s, gone, then visible again from 6 s.
        val detector = FakeDetector(h.nowMs) { id, t -> if (t in 1_000..3_999 || t >= 6_000) found(id) else notFound }
        h.engine.start(VisualWatchTask(listOf(target), detector))
        advanceTimeBy(5_500)
        runCurrent()
        assertEquals("one tap for the first appearance despite ~30 scans", 1, h.gestures.taps.size)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("second appearance triggers again", 2, h.gestures.taps.size)
        assertTrue(detector.scans > 30)
        h.engine.stop()
        advanceUntilIdle()
        assertEquals(2, h.engine.state.value.stats.detections)
    }

    @Test
    fun batchIsNotRestartedByLaterScans() = runTest {
        val h = EngineHarness(this)
        val target = testTarget(
            "A",
            action = TargetActionSettings(DetectionMode.ONCE_PER_APPEARANCE, tapsPerTrigger = 5, rate = TapRate.fromTps(2.0), cooldownMs = 0),
        )
        val detector = FakeDetector(h.nowMs) { id, _ -> found(id) }
        h.engine.start(VisualWatchTask(listOf(target), detector))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(5, h.gestures.taps.size)
        assertEquals(listOf(0L, 500L, 1_000L, 1_500L, 2_000L), h.gestures.taps.map { it.atMs })
        h.engine.stop()
        advanceUntilIdle()
    }

    @Test
    fun tapsGoToDetectedPosition() = runTest {
        val h = EngineHarness(this)
        val target = testTarget("A")
        val detector = FakeDetector(h.nowMs) { id, _ -> found(id, x = 777, y = 1234) }
        h.engine.start(VisualWatchTask(listOf(target), detector))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(777, h.gestures.taps.first().x)
        assertEquals(1234, h.gestures.taps.first().y)
        h.engine.stop()
        advanceUntilIdle()
    }

    @Test
    fun repeatWhileVisibleStopsWhenTargetGone() = runTest {
        val h = EngineHarness(this)
        val target = testTarget(
            "A",
            match = MatchSettings(scanIntervalMs = 200),
            action = TargetActionSettings(DetectionMode.REPEAT_WHILE_VISIBLE, rate = TapRate.fromTps(10.0), maxTapsPerAppearance = 1_000, cooldownMs = 0),
        )
        val detector = FakeDetector(h.nowMs) { id, t -> if (t < 2_000) found(id) else notFound }
        h.engine.start(VisualWatchTask(listOf(target), detector))
        advanceTimeBy(5_000)
        runCurrent()
        val taps = h.gestures.taps
        assertTrue("taps while visible: ${taps.size}", taps.size in 15..22)
        assertTrue(taps.all { it.atMs < 2_300 })
        h.engine.stop()
        advanceUntilIdle()
    }

    @Test
    fun waitAndClickFailsAfterRetries() = runTest {
        val h = EngineHarness(this)
        val target = testTarget(
            "B",
            match = MatchSettings(scanIntervalMs = 100, timeoutMs = 1_000, retryCount = 2),
            action = TargetActionSettings(DetectionMode.WAIT_AND_CLICK, cooldownMs = 0),
        )
        val detector = FakeDetector(h.nowMs) { _, _ -> notFound }
        h.engine.start(VisualWatchTask(listOf(target), detector))
        advanceUntilIdle()
        val s = h.engine.state.value
        assertEquals(RunStatus.FAILED, s.status)
        assertEquals(2, s.stats.retries)
        assertEquals(3, s.stats.detectionTimeouts)
        assertTrue(currentTime in 3_000..3_200)
    }

    @Test
    fun waitAndClickCompletesWhenFound() = runTest {
        val h = EngineHarness(this)
        val target = testTarget(
            "B",
            match = MatchSettings(scanIntervalMs = 100, timeoutMs = 10_000),
            action = TargetActionSettings(DetectionMode.WAIT_AND_CLICK, tapsPerTrigger = 2, rate = TapRate.fromTps(5.0), cooldownMs = 0),
        )
        val detector = FakeDetector(h.nowMs) { id, t -> if (t >= 1_500) found(id) else notFound }
        h.engine.start(VisualWatchTask(listOf(target), detector))
        advanceUntilIdle()
        assertEquals(RunStatus.COMPLETED, h.engine.state.value.status)
        assertEquals(2, h.gestures.taps.size)
    }

    @Test
    fun notifyOnlyNeverTaps() = runTest {
        val h = EngineHarness(this)
        h.gestures.available.value = false // not needed for notify-only targets
        val target = testTarget("N", DetectionMode.NOTIFY_ONLY)
        val detector = FakeDetector(h.nowMs) { id, _ -> found(id) }
        h.engine.start(VisualWatchTask(listOf(target), detector))
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(h.gestures.taps.isEmpty())
        assertTrue(h.engine.state.value.events.any { it.type == EventType.NOTIFY })
        h.engine.stop()
        advanceUntilIdle()
    }

    @Test
    fun ambiguousDetectionSkipsTapAndLogs() = runTest {
        val h = EngineHarness(this)
        val target = testTarget("A")
        val candidates = listOf(MatchCandidate(PixelRect(0, 0, 10, 10), 0.95f), MatchCandidate(PixelRect(500, 500, 10, 10), 0.95f))
        val detector = FakeDetector(h.nowMs) { _, _ -> DetectionOutcome.Ambiguous(candidates, "2 similar matches") }
        h.engine.start(VisualWatchTask(listOf(target), detector))
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(h.gestures.taps.isEmpty())
        assertTrue(h.engine.state.value.stats.ambiguousDetections > 0)
        h.engine.stop()
        advanceUntilIdle()
    }

    @Test
    fun blankFramesDoNotReArmTarget() = runTest {
        val h = EngineHarness(this)
        val target = testTarget("A")
        val detector = FakeDetector(h.nowMs) { id, t ->
            when {
                t < 500 -> found(id)
                t < 2_000 -> DetectionOutcome.Unavailable("blank frame")
                else -> found(id)
            }
        }
        h.engine.start(VisualWatchTask(listOf(target), detector))
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, h.gestures.taps.size)
        h.engine.stop()
        advanceUntilIdle()
    }

    @Test
    fun twoTargetsAreBothServed() = runTest {
        val h = EngineHarness(this)
        val a = testTarget("A", DetectionMode.REPEAT_WHILE_VISIBLE)
        val b = testTarget("B", DetectionMode.ONCE_PER_APPEARANCE)
        val detector = FakeDetector(h.nowMs) { id, _ -> if (id == "A") found(id, x = 100) else found(id, x = 900) }
        h.engine.start(VisualWatchTask(listOf(a, b), detector))
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue("target B must not be starved", h.gestures.taps.any { it.x == 900 })
        assertTrue(h.gestures.taps.count { it.x == 100 } > 3)
        h.engine.stop()
        advanceUntilIdle()
    }
}
