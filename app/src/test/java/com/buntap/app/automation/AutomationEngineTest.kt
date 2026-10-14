package com.buntap.app.automation

import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.SessionKind
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

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineTest {

    private fun endless() = testTask { ctx ->
        ctx.tapBatch(TapBatchRequest("P", 1, 1, null, TapRate.fromTps(2.0)))
        TaskOutcome(true, "unreachable")
    }

    @Test
    fun onlyOneSessionAtATime() = runTest {
        val h = EngineHarness(this)
        assertEquals(StartResult.Started, h.engine.start(endless()))
        val second = h.engine.start(endless())
        assertTrue(second is StartResult.Rejected)
        h.engine.stop()
        advanceUntilIdle()
        assertEquals(StartResult.Started, h.engine.start(testTask { TaskOutcome(true, "ok") }))
        advanceUntilIdle()
    }

    @Test
    fun rejectsStartWithoutAccessibility() = runTest {
        val h = EngineHarness(this)
        h.gestures.available.value = false
        val r = h.engine.start(endless())
        assertTrue(r is StartResult.Rejected && r.reason.contains("accessibility"))
        // Tasks that never tap may still run.
        assertEquals(StartResult.Started, h.engine.start(testTask(needsGestures = false) { TaskOutcome(true, "ok") }))
        advanceUntilIdle()
    }

    @Test
    fun rejectsVisualTaskWithoutCapture() = runTest {
        val h = EngineHarness(this)
        h.env.captureActive.value = false
        val r = h.engine.start(testTask(requiresCapture = true) { TaskOutcome(true, "ok") })
        assertTrue(r is StartResult.Rejected && r.reason.contains("capture"))
    }

    @Test
    fun countdownDelaysFirstTap() = runTest {
        val h = EngineHarness(this)
        h.engine.start(testTask(startDelayMs = 3_000) { ctx ->
            ctx.tapBatch(TapBatchRequest("P", 1, 1, 1, TapRate.fromTps(5.0)))
            TaskOutcome(true, "ok")
        })
        runCurrent()
        assertEquals(RunStatus.COUNTDOWN, h.engine.state.value.status)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(h.engine.state.value.countdownRemainingMs in 1_900..2_100)
        advanceUntilIdle()
        assertEquals(3_000L, h.gestures.taps.single().atMs)
    }

    @Test
    fun stopDuringCountdownNeverTaps() = runTest {
        val h = EngineHarness(this)
        h.engine.start(testTask(startDelayMs = 3_000) { ctx ->
            ctx.tapBatch(TapBatchRequest("P", 1, 1, 1, TapRate.fromTps(5.0)))
            TaskOutcome(true, "ok")
        })
        advanceTimeBy(500)
        h.engine.stop()
        advanceUntilIdle()
        assertTrue(h.gestures.taps.isEmpty())
        assertEquals(RunStatus.STOPPED, h.engine.state.value.status)
    }

    @Test
    fun accessibilityLossStopsRunningSession() = runTest {
        val h = EngineHarness(this)
        h.engine.start(endless())
        advanceTimeBy(1_100)
        runCurrent()
        h.gestures.available.value = false
        advanceUntilIdle()
        val s = h.engine.state.value
        assertEquals(StopReason.ACCESSIBILITY_LOST, s.stopReason)
        assertEquals(RunStatus.FAILED, s.status)
    }

    @Test
    fun captureLossStopsVisualSession() = runTest {
        val h = EngineHarness(this)
        h.engine.start(testTask(requiresCapture = true, needsGestures = false) { ctx ->
            ctx.sleep(60_000)
            TaskOutcome(true, "ok")
        })
        advanceTimeBy(1_000)
        h.env.captureActive.value = false
        advanceUntilIdle()
        assertEquals(StopReason.CAPTURE_LOST, h.engine.state.value.stopReason)
        assertTrue(currentTime < 2_000)
    }

    @Test
    fun cancellationInterruptsLongWaitPromptly() = runTest {
        val h = EngineHarness(this)
        h.engine.start(testTask(needsGestures = false) { ctx ->
            ctx.sleep(600_000)
            TaskOutcome(true, "ok")
        })
        advanceTimeBy(100)
        h.engine.stop()
        advanceUntilIdle()
        assertEquals(RunStatus.STOPPED, h.engine.state.value.status)
        assertTrue("stop must not wait for the sleep", currentTime < 1_000)
    }

    @Test
    fun unexpectedExceptionIsReportedAsFailure() = runTest {
        val h = EngineHarness(this)
        h.engine.start(testTask(needsGestures = false) { throw IllegalStateException("boom") })
        advanceUntilIdle()
        val s = h.engine.state.value
        assertEquals(RunStatus.FAILED, s.status)
        assertTrue(s.message!!.contains("boom"))
    }

    @Test
    fun historyIsRecordedWithStats() = runTest {
        val h = EngineHarness(this)
        h.engine.start(testTask { ctx ->
            ctx.tapBatch(TapBatchRequest("P", 1, 1, 4, TapRate.fromTps(4.0)))
            TaskOutcome(true, "all good")
        })
        advanceUntilIdle()
        val record = h.recorder.records.single()
        assertEquals(SessionKind.CLICKER, record.kind)
        assertEquals(RunStatus.COMPLETED, record.status)
        assertEquals(4, record.stats.completed)
        assertEquals(4.0, record.stats.averageObservedTps!!, 1e-6)
        assertEquals("all good", record.message)
        assertTrue(record.events.isNotEmpty())
    }

    @Test
    fun armedTaskStartsFromPanel() = runTest {
        val h = EngineHarness(this)
        assertTrue(h.engine.startArmed() is StartResult.Rejected)
        h.engine.arm(ArmedTask("Armed", SessionKind.CLICKER) { PreparedTask.Invalid("bad config") })
        val invalid = h.engine.startArmed()
        assertTrue(invalid is StartResult.Rejected && invalid.reason == "bad config")
        h.engine.arm(ArmedTask("Armed", SessionKind.CLICKER) {
            PreparedTask.Ready(testTask { ctx ->
                ctx.tapBatch(TapBatchRequest("P", 1, 1, 2, TapRate.fromTps(5.0)))
                TaskOutcome(true, "ok")
            })
        })
        assertEquals(StartResult.Started, h.engine.startArmed())
        advanceUntilIdle()
        assertEquals(2, h.gestures.taps.size)
    }

    @Test
    fun elapsedTimeExcludesPauses() = runTest {
        val h = EngineHarness(this)
        h.engine.start(endless())
        advanceTimeBy(1_000)
        runCurrent()
        h.engine.pause()
        advanceTimeBy(4_000)
        runCurrent()
        h.engine.resume()
        advanceTimeBy(1_000)
        h.engine.stop()
        advanceUntilIdle()
        val active = h.engine.state.value.stats.activeElapsedMs
        assertTrue("active=$active", active in 1_900..2_100)
    }
}
