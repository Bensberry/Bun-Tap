package com.buntap.app.automation

import com.buntap.app.domain.model.ClickerConfig
import com.buntap.app.domain.model.RepeatMode
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.SettingsMode
import com.buntap.app.domain.model.StopReason
import com.buntap.app.domain.model.TapPoint
import com.buntap.app.domain.model.TapRate
import com.buntap.app.testing.EngineHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClickerTaskTest {

    private val screen = ScreenGeometry(1080, 2400)

    private fun p(id: String, x: Int, count: Int? = null, tps: Double? = null, timeout: Long? = null) =
        TapPoint(id, "P$id", x, 100, countOverride = count, rateOverride = tps?.let { TapRate.fromTps(it) }, timeoutMs = timeout)

    private fun config(vararg points: TapPoint) = ClickerConfig(
        points = points.toList(),
        globalRate = TapRate.fromTps(10.0),
        tapsPerPoint = 3,
        startDelayMs = 0,
        reference = screen,
    )

    @Test
    fun globalModeTapsEachPointInOrder() = runTest {
        val h = EngineHarness(this)
        h.engine.start(ClickerTask(config(p("1", 10), p("2", 20))))
        advanceUntilIdle()
        assertEquals(listOf(10, 10, 10, 20, 20, 20), h.gestures.taps.map { it.x })
        val gaps = h.gestures.taps.map { it.atMs }.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it == 100L })
        assertEquals(RunStatus.COMPLETED, h.engine.state.value.status)
        assertEquals(6, h.engine.state.value.stats.plannedTaps)
        assertEquals(0, h.engine.state.value.stats.remainingTaps)
    }

    @Test
    fun perPointOverridesApply() = runTest {
        val h = EngineHarness(this)
        val c = config(p("1", 10, count = 2, tps = 2.0), p("2", 20)).copy(settingsMode = SettingsMode.PER_POINT)
        h.engine.start(ClickerTask(c))
        advanceUntilIdle()
        assertEquals(listOf(10, 10, 20, 20, 20), h.gestures.taps.map { it.x })
        val times = h.gestures.taps.map { it.atMs }
        assertEquals(500L, times[1] - times[0]) // 2 TPS for point 1
        assertEquals(100L, times[3] - times[2]) // global 10 TPS for point 2
    }

    @Test
    fun fixedPassesRepeatSequence() = runTest {
        val h = EngineHarness(this)
        val c = config(p("1", 10), p("2", 20)).copy(tapsPerPoint = 1, repeatMode = RepeatMode.FIXED_PASSES, passes = 3)
        h.engine.start(ClickerTask(c))
        advanceUntilIdle()
        assertEquals(listOf(10, 20, 10, 20, 10, 20), h.gestures.taps.map { it.x })
    }

    @Test
    fun untilStoppedRunsUntilSessionLimit() = runTest {
        val h = EngineHarness(this)
        val c = config(p("1", 10), p("2", 20)).copy(tapsPerPoint = 1, repeatMode = RepeatMode.UNTIL_STOPPED, sessionTapLimit = 7)
        h.engine.start(ClickerTask(c))
        advanceUntilIdle()
        assertEquals(7, h.gestures.taps.size)
        assertEquals(StopReason.COMPLETED, h.engine.state.value.stopReason)
    }

    @Test
    fun untilStoppedCanBeStoppedByUser() = runTest {
        val h = EngineHarness(this)
        val c = config(p("1", 10)).copy(repeatMode = RepeatMode.UNTIL_STOPPED)
        h.engine.start(ClickerTask(c))
        advanceTimeBy(2_050)
        runCurrent()
        h.engine.stop()
        advanceUntilIdle()
        assertEquals(21, h.gestures.taps.size)
        assertEquals(RunStatus.STOPPED, h.engine.state.value.status)
    }

    @Test
    fun pointTimeoutMovesOn() = runTest {
        val h = EngineHarness(this)
        val c = config(p("1", 10, timeout = 250), p("2", 20)).copy(tapsPerPoint = 10)
        h.engine.start(ClickerTask(c))
        advanceUntilIdle()
        assertEquals(3, h.gestures.taps.count { it.x == 10 }) // 0, 100, 200 then timeout at 250
        assertEquals(10, h.gestures.taps.count { it.x == 20 })
    }

    @Test
    fun rotationBeforeStartIsRefused() = runTest {
        val h = EngineHarness(this)
        h.env.geometry = ScreenGeometry(2400, 1080, rotation = 1)
        h.engine.start(ClickerTask(config(p("1", 10))))
        advanceUntilIdle()
        assertTrue(h.gestures.taps.isEmpty())
        assertEquals(StopReason.DISPLAY_CHANGED, h.engine.state.value.stopReason)
    }

    @Test
    fun rotationDuringRunStopsBeforeNextPoint() = runTest {
        val h = EngineHarness(this)
        h.engine.start(ClickerTask(config(p("1", 10), p("2", 20))))
        advanceTimeBy(150)
        runCurrent()
        h.env.geometry = ScreenGeometry(2400, 1080, rotation = 1)
        advanceUntilIdle()
        // Taps at 0 and 100 ms happened before the rotation; the one due at 200 ms is refused.
        assertEquals(2, h.gestures.taps.size)
        assertTrue(h.gestures.taps.none { it.x == 20 })
        assertEquals(StopReason.DISPLAY_CHANGED, h.engine.state.value.stopReason)
    }
}
