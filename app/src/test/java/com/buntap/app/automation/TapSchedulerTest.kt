package com.buntap.app.automation

import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.StopReason
import com.buntap.app.domain.model.TapRate
import com.buntap.app.testing.EngineHarness
import com.buntap.app.testing.testTask
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scheduler behaviour on virtual time with a fake gesture dispatcher. These tests verify the
 * scheduling logic; they do not prove how fast a real device delivers gestures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TapSchedulerTest {

    private fun batchTask(count: Int?, tps: Double, onResult: (BatchResult) -> Unit = {}) = testTask { ctx ->
        val r = ctx.tapBatch(TapBatchRequest("P", 10, 20, count, TapRate.fromTps(tps)))
        onResult(r)
        if (r.outcome == BatchOutcome.FAILED) TaskOutcome(false, r.message ?: "failed") else TaskOutcome(true, "done")
    }

    @Test
    fun finiteBatchTapsAtRequestedInterval() = runTest {
        val h = EngineHarness(this)
        var result: BatchResult? = null
        h.engine.start(batchTask(10, 5.0) { result = it })
        advanceUntilIdle()

        assertEquals(10, h.gestures.taps.size)
        assertEquals((0 until 10).map { it * 200L }, h.gestures.taps.map { it.atMs })
        assertEquals(BatchOutcome.COMPLETED, result!!.outcome)
        val state = h.engine.state.value
        assertEquals(RunStatus.COMPLETED, state.status)
        assertEquals(10, state.stats.completed)
        assertEquals(10, state.stats.dispatched)
        assertEquals(10, state.stats.scheduled)
        assertEquals(5.0, state.stats.averageObservedTps!!, 1e-6)
        assertEquals(5.0, state.stats.requestedTps!!, 1e-6)
        assertEquals(0, state.stats.missedDeadlines)
    }

    @Test
    fun countersDistinguishFailedGestures() = runTest {
        val h = EngineHarness(this)
        h.gestures.queuedResults.addAll(listOf(GestureResult.CANCELLED, GestureResult.REJECTED, GestureResult.TIMED_OUT))
        h.engine.start(batchTask(3, 10.0))
        advanceUntilIdle()
        val s = h.engine.state.value.stats
        // 3 failures + 3 successes needed to complete 3 taps.
        assertEquals(3, s.completed)
        assertEquals(1, s.cancelled)
        assertEquals(1, s.rejected)
        assertEquals(1, s.timedOut)
        assertEquals(6, s.scheduled)
        assertEquals(5, s.dispatched) // the rejected one was never dispatched
    }

    @Test
    fun consecutiveFailuresFailTheBatch() = runTest {
        val h = EngineHarness(this)
        repeat(TapScheduler.MAX_CONSECUTIVE_FAILURES) { h.gestures.queuedResults.add(GestureResult.CANCELLED) }
        var result: BatchResult? = null
        h.engine.start(batchTask(10, 10.0) { result = it })
        advanceUntilIdle()
        assertEquals(BatchOutcome.FAILED, result!!.outcome)
        assertEquals(RunStatus.FAILED, h.engine.state.value.status)
    }

    @Test
    fun unavailableServiceEndsSession() = runTest {
        val h = EngineHarness(this)
        h.gestures.queuedResults.add(GestureResult.UNAVAILABLE)
        h.engine.start(batchTask(5, 10.0))
        advanceUntilIdle()
        val s = h.engine.state.value
        assertEquals(RunStatus.FAILED, s.status)
        assertEquals(StopReason.ACCESSIBILITY_LOST, s.stopReason)
    }

    @Test
    fun slowGesturesCountMissedDeadlinesWithoutBursting() = runTest {
        val h = EngineHarness(this)
        h.gestures.extraLatencyMs = 250 // each gesture takes 260 ms but 10 TPS needs 100 ms
        h.engine.start(batchTask(6, 10.0))
        advanceUntilIdle()
        val taps = h.gestures.taps.map { it.atMs }
        val gaps = taps.zipWithNext { a, b -> b - a }
        assertTrue("never faster than requested: $gaps", gaps.all { it >= 100 })
        val s = h.engine.state.value.stats
        assertTrue(s.missedDeadlines > 0)
        assertTrue(s.averageObservedTps!! < 5.0)
        assertEquals(10.0, s.requestedTps!!, 1e-9)
    }

    @Test
    fun oneSlowGestureDoesNotCauseCatchUpBurst() = runTest {
        val h = EngineHarness(this)
        // The first gesture reports back after 160 ms (slot 2 at 100 ms is missed by 60 ms).
        h.gestures.extraLatencyQueue.add(150)
        h.engine.start(batchTask(6, 10.0))
        advanceUntilIdle()
        val gaps = h.gestures.taps.map { it.atMs }.zipWithNext { a, b -> b - a }
        // Old behaviour dispatched tap 3 only 40 ms after tap 2. Now every gap is >= interval - 2 ms.
        assertTrue("gaps $gaps", gaps.all { it >= 98 })
        assertEquals(6, h.gestures.taps.size)
    }

    @Test
    fun rateAboveMaximumIsClamped() = runTest {
        val h = EngineHarness(this)
        h.engine.start(batchTask(4, 40.0)) // max is 20 TPS by default
        advanceUntilIdle()
        val gaps = h.gestures.taps.map { it.atMs }.zipWithNext { a, b -> b - a }
        assertEquals(listOf(50L, 50L, 50L), gaps)
        assertTrue(h.engine.state.value.events.any { it.type == EventType.WARNING && it.message.contains("exceeds") })
    }

    @Test
    fun pauseStopsTapsAndResumeContinuesWithoutBurst() = runTest {
        val h = EngineHarness(this)
        h.engine.start(batchTask(null, 10.0))
        advanceTimeBy(350)
        runCurrent()
        assertEquals(4, h.gestures.taps.size) // 0, 100, 200, 300
        h.engine.pause()
        runCurrent()
        assertEquals(RunStatus.PAUSED, h.engine.state.value.status)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("no taps while paused", 4, h.gestures.taps.size)
        h.engine.resume()
        advanceTimeBy(300)
        runCurrent()
        val afterResume = h.gestures.taps.drop(4).map { it.atMs }
        assertTrue(afterResume.isNotEmpty())
        assertTrue(afterResume.zipWithNext { a, b -> b - a }.all { it >= 100 })
        h.engine.stop()
        advanceUntilIdle()
        assertEquals(RunStatus.STOPPED, h.engine.state.value.status)
    }

    @Test
    fun stopCancelsPromptlyAndNoTapsFollow() = runTest {
        val h = EngineHarness(this)
        h.engine.start(batchTask(null, 5.0))
        advanceTimeBy(450)
        runCurrent()
        val before = h.gestures.taps.size
        h.engine.stop()
        advanceUntilIdle()
        assertEquals(before, h.gestures.taps.size)
        val s = h.engine.state.value
        assertEquals(RunStatus.STOPPED, s.status)
        assertEquals(StopReason.USER_STOP, s.stopReason)
        assertEquals(StopReason.USER_STOP, h.recorder.records.single().reason)
    }

    @Test
    fun emergencyStopPreventsFurtherTaps() = runTest {
        val h = EngineHarness(this)
        h.engine.start(batchTask(null, 20.0))
        advanceTimeBy(120)
        runCurrent()
        val before = h.gestures.taps.size
        h.engine.emergencyStop()
        advanceTimeBy(10_000)
        advanceUntilIdle()
        assertEquals(before, h.gestures.taps.size)
        assertEquals(StopReason.EMERGENCY_STOP, h.engine.state.value.stopReason)
        // A new session can start again afterwards.
        assertEquals(StartResult.Started, h.engine.start(batchTask(1, 5.0)))
        advanceUntilIdle()
        assertEquals(before + 1, h.gestures.taps.size)
    }

    @Test
    fun globalSafetyLimitStopsUnlimitedRun() = runTest {
        val h = EngineHarness(this, limits = SafetyLimits(maxTapsPerSession = 7))
        h.engine.start(batchTask(null, 10.0))
        advanceUntilIdle()
        assertEquals(7, h.gestures.taps.size)
        assertEquals(StopReason.TAP_LIMIT, h.engine.state.value.stopReason)
        assertEquals(RunStatus.STOPPED, h.engine.state.value.status)
    }

    @Test
    fun taskTapLimitCompletesSession() = runTest {
        val h = EngineHarness(this)
        val task = testTask(tapLimit = 5) { ctx ->
            ctx.tapBatch(TapBatchRequest("P", 1, 1, null, TapRate.fromTps(10.0)))
            TaskOutcome(true, "unreachable")
        }
        h.engine.start(task)
        advanceUntilIdle()
        assertEquals(5, h.gestures.taps.size)
        val s = h.engine.state.value
        assertEquals(RunStatus.COMPLETED, s.status)
        assertTrue(s.message!!.contains("tap limit"))
    }

    @Test
    fun durationLimitUsesActiveTime() = runTest {
        val h = EngineHarness(this, limits = SafetyLimits(maxSessionDurationMs = 1_000))
        h.engine.start(batchTask(null, 10.0))
        advanceTimeBy(500)
        runCurrent()
        h.engine.pause()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("paused time must not count", h.engine.state.value.status.isActive)
        h.engine.resume()
        advanceUntilIdle()
        val s = h.engine.state.value
        assertEquals(StopReason.DURATION_LIMIT, s.stopReason)
        assertTrue(currentTime in 6_000..6_200)
    }

    @Test
    fun batchTimeoutReportsProgress() = runTest {
        val h = EngineHarness(this)
        var result: BatchResult? = null
        h.engine.start(testTask { ctx ->
            result = ctx.tapBatch(TapBatchRequest("P", 1, 1, 100, TapRate.fromTps(10.0), timeoutMs = 450))
            TaskOutcome(true, "ok")
        })
        advanceUntilIdle()
        assertEquals(BatchOutcome.TIMED_OUT, result!!.outcome)
        assertEquals(5, result!!.completedTaps) // 0,100,200,300,400
    }

    @Test
    fun preAndPostDelaysAreApplied() = runTest {
        val h = EngineHarness(this)
        h.engine.start(testTask { ctx ->
            ctx.tapBatch(TapBatchRequest("A", 1, 1, 1, TapRate.fromTps(10.0), preDelayMs = 300, postDelayMs = 700))
            ctx.tapBatch(TapBatchRequest("B", 2, 2, 1, TapRate.fromTps(10.0)))
            TaskOutcome(true, "ok")
        })
        advanceUntilIdle()
        assertEquals(listOf(300L, 1_010L), h.gestures.taps.map { it.atMs })
    }

    @Test
    fun tapsCoveredByOwnOverlayAreSkipped() = runTest {
        val h = EngineHarness(this)
        h.env.covered = PixelRect(0, 0, 100, 100)
        h.engine.start(batchTask(3, 10.0))
        advanceUntilIdle()
        assertEquals(0, h.gestures.taps.size)
        assertEquals(TapScheduler.MAX_CONSECUTIVE_FAILURES, h.engine.state.value.stats.skipped)
        assertEquals(RunStatus.FAILED, h.engine.state.value.status)
    }

    @Test
    fun offScreenTapsAreSkipped() = runTest {
        val h = EngineHarness(this)
        h.engine.start(testTask { ctx ->
            val r = ctx.tapBatch(TapBatchRequest("Far", 5_000, 5_000, 1, TapRate.fromTps(10.0)))
            TaskOutcome(r.outcome == BatchOutcome.COMPLETED, r.message ?: "")
        })
        advanceUntilIdle()
        assertEquals(0, h.gestures.taps.size)
    }

    @Test
    fun consecutiveBatchesKeepSpacing() = runTest {
        val h = EngineHarness(this)
        h.engine.start(testTask { ctx ->
            repeat(3) { i -> ctx.tapBatch(TapBatchRequest("P$i", i, i, 1, TapRate.fromTps(5.0))) }
            TaskOutcome(true, "ok")
        })
        advanceUntilIdle()
        assertEquals(listOf(0L, 200L, 400L), h.gestures.taps.map { it.atMs })
        assertEquals(5.0, h.engine.state.value.stats.averageObservedTps!!, 1e-6)
    }
}
