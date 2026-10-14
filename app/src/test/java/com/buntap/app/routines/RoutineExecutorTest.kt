package com.buntap.app.routines

import com.buntap.app.automation.SafetyLimits
import com.buntap.app.automation.StartResult
import com.buntap.app.domain.model.ActionRunStatus
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.StopReason
import com.buntap.app.domain.model.TapRate
import com.buntap.app.domain.model.VisualTarget
import com.buntap.app.testing.EngineHarness
import com.buntap.app.testing.FakeDetector
import com.buntap.app.testing.FakeDeviceActions
import com.buntap.app.testing.found
import com.buntap.app.testing.notFound
import com.buntap.app.testing.testTarget
import com.buntap.app.vision.DetectionOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineExecutorTest {

    private val targetA = testTarget("A")
    private val targetB = testTarget("B")
    private val targets: Map<String, VisualTarget> = mapOf("A" to targetA, "B" to targetB)
    private var nextId = 0
    private fun id() = "a${nextId++}"
    private fun tap(x: Int, count: Int = 1, tps: Double = 10.0) =
        TapPointAction(id(), x, 50, TapSpec(count, TapRate.fromTps(tps)))

    private class Run(val h: EngineHarness, val detector: FakeDetector, val device: FakeDeviceActions)

    private fun TestScope.run(
        actions: List<RoutineAction>,
        limits: SafetyLimits = SafetyLimits(),
        maxTotalTaps: Int? = null,
        stepMode: Boolean = false,
        script: (String, Long) -> DetectionOutcome = { _, _ -> notFound },
    ): Run {
        val h = EngineHarness(this, limits)
        val detector = FakeDetector(h.nowMs, script)
        val device = FakeDeviceActions()
        val routine = Routine("r", "Test routine", "", RoutineBody(actions = actions, maxTotalTaps = maxTotalTaps), 0, 0)
        val result = h.engine.start(RoutineTask(routine, targets, detector, device, stepMode = stepMode))
        assertEquals(StartResult.Started, result)
        return Run(h, detector, device)
    }

    @Test
    fun runsActionsSequentially() = runTest {
        val wait = WaitAction(id(), 1_000)
        val t = tap(10, count = 2, tps = 5.0)
        val log = LogAction(id(), "done")
        val r = run(listOf(wait, t, log))
        advanceUntilIdle()
        assertEquals(listOf(1_000L, 1_200L), r.h.gestures.taps.map { it.atMs })
        val s = r.h.engine.state.value
        assertEquals(RunStatus.COMPLETED, s.status)
        assertEquals(ActionRunStatus.SUCCEEDED, s.actionStatuses[wait.id])
        assertEquals(ActionRunStatus.SUCCEEDED, s.actionStatuses[log.id])
    }

    @Test
    fun ifTargetTakesThenOrElseBranch() = runTest {
        val action = IfTargetAction(id(), "A", expectPresent = true, timeoutMs = 500, thenActions = listOf(tap(1)), elseActions = listOf(tap(2)))
        val present = run(listOf(action)) { id, _ -> if (id == "A") found(id) else notFound }
        advanceUntilIdle()
        assertEquals(listOf(1), present.h.gestures.taps.map { it.x })

        val absent = run(listOf(action.copy(id = id()))) { _, _ -> notFound }
        advanceUntilIdle()
        assertEquals(listOf(2), absent.h.gestures.taps.map { it.x })
        assertEquals(RunStatus.COMPLETED, absent.h.engine.state.value.status)
    }

    @Test
    fun ifTargetDoesNotExistBranch() = runTest {
        val action = IfTargetAction(id(), "A", expectPresent = false, timeoutMs = 300, thenActions = listOf(tap(7)))
        val r = run(listOf(action)) { _, _ -> notFound }
        advanceUntilIdle()
        assertEquals(listOf(7), r.h.gestures.taps.map { it.x })
    }

    @Test
    fun repeatRunsGroupExactly() = runTest {
        val r = run(listOf(RepeatAction(id(), times = 4, actions = listOf(tap(1), tap(2)))))
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 1, 2, 1, 2, 1, 2), r.h.gestures.taps.map { it.x })
    }

    @Test
    fun retrySucceedsOnLaterAttempt() = runTest {
        val retry = RetryAction(id(), maxAttempts = 3, delayBetweenMs = 500, actions = listOf(WaitForTargetAction(id(), "B", timeoutMs = 1_000), TapTargetAction(id(), "B", useLastDetection = true)))
        val r = run(listOf(retry)) { id, t -> if (id == "B" && t >= 1_500) found(id, x = 444) else notFound }
        advanceUntilIdle()
        val s = r.h.engine.state.value
        assertEquals(RunStatus.COMPLETED, s.status)
        assertEquals(1, s.stats.retries)
        assertEquals(listOf(444), r.h.gestures.taps.map { it.x })
    }

    @Test
    fun retryExhaustedFailsRoutine() = runTest {
        val retry = RetryAction(id(), maxAttempts = 3, delayBetweenMs = 0, actions = listOf(WaitForTargetAction(id(), "B", timeoutMs = 1_000)))
        val after = tap(9)
        val r = run(listOf(retry, after))
        advanceUntilIdle()
        val s = r.h.engine.state.value
        assertEquals(RunStatus.FAILED, s.status)
        assertEquals(StopReason.ROUTINE_FAILED, s.stopReason)
        assertTrue(s.message!!.contains("3 attempt"))
        assertEquals(2, s.stats.retries)
        assertTrue(r.h.gestures.taps.isEmpty())
        assertTrue(currentTime in 3_000..3_200)
    }

    @Test
    fun timeLimitFailsGroupAndPolicyDecides() = runTest {
        val limited = TimeLimitAction(id(), timeoutMs = 1_000, actions = listOf(WaitAction(id(), 5_000)), onFailure = FailurePolicy.CONTINUE)
        val r = run(listOf(limited, tap(3)))
        advanceUntilIdle()
        assertEquals(listOf(1_000L), r.h.gestures.taps.map { it.atMs })
        val s = r.h.engine.state.value
        assertEquals(RunStatus.COMPLETED, s.status)
        assertEquals(ActionRunStatus.FAILED, s.actionStatuses[limited.id])
        assertEquals(1, s.stats.failedActions)
    }

    @Test
    fun stopActionEndsRoutine() = runTest {
        val r = run(listOf(tap(1), StopAction(id(), success = true, message = "Finished early"), tap(2)))
        advanceUntilIdle()
        assertEquals(listOf(1), r.h.gestures.taps.map { it.x })
        assertEquals("Finished early", r.h.engine.state.value.message)
        assertEquals(RunStatus.COMPLETED, r.h.engine.state.value.status)
    }

    @Test
    fun stopActionWithFailure() = runTest {
        val r = run(listOf(StopAction(id(), success = false, message = "Nope")))
        advanceUntilIdle()
        assertEquals(RunStatus.FAILED, r.h.engine.state.value.status)
    }

    @Test
    fun continueIfTargetSkipsRestOfGroup() = runTest {
        val group = RepeatAction(
            id(), times = 3,
            actions = listOf(tap(1), ContinueIfTargetAction(id(), "A", timeoutMs = 0, whenMissing = MissingTargetBehavior.SKIP_REST_OF_GROUP), tap(2)),
        )
        val r = run(listOf(group, tap(5)))
        advanceUntilIdle()
        assertEquals(listOf(1, 1, 1, 5), r.h.gestures.taps.map { it.x })
    }

    @Test
    fun continueIfTargetStopsWhenMissing() = runTest {
        val r = run(listOf(ContinueIfTargetAction(id(), "A", timeoutMs = 0, whenMissing = MissingTargetBehavior.STOP_FAILURE), tap(2)))
        advanceUntilIdle()
        assertTrue(r.h.gestures.taps.isEmpty())
        assertEquals(RunStatus.FAILED, r.h.engine.state.value.status)
    }

    @Test
    fun continuePolicyIgnoresFailure() = runTest {
        val key = PressKeyAction(id(), GlobalKey.BACK, onFailure = FailurePolicy.CONTINUE)
        val r = run(listOf(key, tap(4)))
        r.device.keyResult = DeviceActionResult(false, "not supported")
        advanceUntilIdle()
        assertEquals(listOf(4), r.h.gestures.taps.map { it.x })
        assertEquals(1, r.h.engine.state.value.stats.failedActions)
        assertEquals(ActionRunStatus.FAILED, r.h.engine.state.value.actionStatuses[key.id])
    }

    @Test
    fun disabledActionsAreSkipped() = runTest {
        val disabled = tap(1).copy(enabled = false)
        val r = run(listOf(disabled, tap(2)))
        advanceUntilIdle()
        assertEquals(listOf(2), r.h.gestures.taps.map { it.x })
        assertEquals(ActionRunStatus.SKIPPED, r.h.engine.state.value.actionStatuses[disabled.id])
    }

    @Test
    fun waitingForMissingTargetIsCancelledPromptly() = runTest {
        val r = run(listOf(WaitForTargetAction(id(), "A", timeoutMs = 3_600_000)))
        advanceTimeBy(2_000)
        runCurrent()
        r.h.engine.stop()
        advanceUntilIdle()
        assertEquals(RunStatus.STOPPED, r.h.engine.state.value.status)
        assertTrue(currentTime < 3_000)
    }

    @Test
    fun routineTapLimitIsEnforced() = runTest {
        val r = run(listOf(RepeatAction(id(), times = 100, actions = listOf(tap(1)))), maxTotalTaps = 5)
        advanceUntilIdle()
        assertEquals(5, r.h.gestures.taps.size)
        assertEquals(RunStatus.COMPLETED, r.h.engine.state.value.status)
    }

    @Test
    fun globalSafetyLimitAppliesToRoutines() = runTest {
        val r = run(listOf(RepeatAction(id(), times = 100, actions = listOf(tap(1)))), limits = SafetyLimits(maxTapsPerSession = 3))
        advanceUntilIdle()
        assertEquals(3, r.h.gestures.taps.size)
        assertEquals(StopReason.TAP_LIMIT, r.h.engine.state.value.stopReason)
    }

    @Test
    fun stepModePausesBeforeEachAction() = runTest {
        val r = run(listOf(tap(1), tap(2), tap(3)), stepMode = true)
        runCurrent()
        assertEquals(RunStatus.PAUSED, r.h.engine.state.value.status)
        assertTrue(r.h.gestures.taps.isEmpty())
        r.h.engine.step()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1), r.h.gestures.taps.map { it.x })
        assertEquals(RunStatus.PAUSED, r.h.engine.state.value.status)
        r.h.engine.step()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1, 2), r.h.gestures.taps.map { it.x })
        r.h.engine.setStepMode(false)
        r.h.engine.resume()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), r.h.gestures.taps.map { it.x })
        assertEquals(RunStatus.COMPLETED, r.h.engine.state.value.status)
    }

    @Test
    fun typedTextIsNeverLogged() = runTest {
        val r = run(listOf(TypeTextAction(id(), "secret-value")))
        advanceUntilIdle()
        assertEquals(listOf("secret-value"), r.device.typed)
        assertFalse(r.h.engine.state.value.events.any { it.message.contains("secret-value") })
        assertFalse(r.h.recorder.records.single().events.any { it.message.contains("secret-value") })
    }

    @Test
    fun exampleRoutineFromSpecification() = runTest {
        val routine = ExampleRoutine.create(targetA, targetB, now = 0)
        val h = EngineHarness(this)
        // Target A is visible; Target B only appears after the first 10 s attempt has expired.
        val detector = FakeDetector(h.nowMs) { id, t ->
            when {
                id == "A" -> found(id, x = 100, y = 100)
                id == "B" && t >= 15_000 -> found(id, x = 900, y = 900)
                else -> notFound
            }
        }
        val device = FakeDeviceActions()
        h.engine.start(RoutineTask(routine, targets, detector, device))
        advanceUntilIdle()

        val s = h.engine.state.value
        assertEquals(RunStatus.COMPLETED, s.status)
        assertEquals("Example routine completed", s.message)
        val aTaps = h.gestures.taps.filter { it.x == 100 }
        val bTaps = h.gestures.taps.filter { it.x == 900 }
        assertEquals(3, aTaps.size)
        assertEquals(listOf(200L, 200L), aTaps.map { it.atMs }.zipWithNext { a, b -> b - a }) // 5 TPS
        assertEquals(5, bTaps.size)
        assertTrue(bTaps.map { it.atMs }.zipWithNext { a, b -> b - a }.all { it == 500L }) // 2 TPS
        assertEquals(2_000L, aTaps.first().atMs)
        assertEquals(1, s.stats.retries)
        assertEquals(1, device.notifications.size)
    }

    @Test
    fun exampleRoutineFailsWhenTargetBNeverAppears() = runTest {
        val routine = ExampleRoutine.create(targetA, targetB, now = 0)
        val h = EngineHarness(this)
        val detector = FakeDetector(h.nowMs) { id, _ -> if (id == "A") found(id) else notFound }
        h.engine.start(RoutineTask(routine, targets, detector, FakeDeviceActions()))
        advanceUntilIdle()
        val s = h.engine.state.value
        assertEquals(RunStatus.FAILED, s.status)
        assertEquals(2, s.stats.retries)
        assertTrue(s.message!!.contains("3 attempt"))
    }
}
