package com.buntap.app

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buntap.app.accessibility.AccessibilityBridge
import com.buntap.app.automation.AutomationTask
import com.buntap.app.automation.ExecutionContext
import com.buntap.app.automation.StartResult
import com.buntap.app.automation.TapBatchRequest
import com.buntap.app.automation.TaskOutcome
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.domain.model.TapRate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * End-to-end: the real engine dispatches real accessibility gestures at a real view and the
 * test counts the touches that actually arrive. Requires the BunTap accessibility service to be
 * enabled on the test device; the test is skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val container get() =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BunTapApplication).container

    /**
     * `am instrument` restarts the app process and Android does not rebind an already-enabled
     * accessibility service into the new process. If the service is enabled in settings but not
     * connected, toggle it off and on (test devices only) so it binds to this process.
     */
    private fun waitForService(): Boolean = runBlocking {
        if (AccessibilityBridge.connected.value) return@runBlocking true
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        if (!com.buntap.app.accessibility.BunTapAccessibilityService.isEnabledInSettings(ctx)) return@runBlocking false
        val ui = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component = "${ctx.packageName}/${com.buntap.app.accessibility.BunTapAccessibilityService::class.java.name}"
        fun shell(cmd: String) = ui.executeShellCommand(cmd).close()
        shell("settings put secure enabled_accessibility_services null")
        SystemClock.sleep(1_000)
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        withTimeoutOrNull(15_000) { AccessibilityBridge.connected.first { it } } ?: false
    }

    private fun runBatch(count: Int, tps: Double): Pair<List<Long>, com.buntap.app.automation.EngineState> {
        val downs = Collections.synchronizedList(mutableListOf<Long>())
        compose.setContent {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        // Event time (when the touch happened), not when the UI thread got to it.
                        downs += awaitFirstDown().uptimeMillis
                    }
                },
            )
        }
        compose.waitForIdle()
        val g = container.displayMonitor.refresh()
        val task = object : AutomationTask {
            override val name = "Gesture test"
            override val kind = SessionKind.CLICKER
            override suspend fun run(ctx: ExecutionContext): TaskOutcome {
                val r = ctx.tapBatch(TapBatchRequest("center", g.widthPx / 2, g.heightPx / 2, count, TapRate.fromTps(tps)))
                return TaskOutcome(r.completedTaps == count, "${r.completedTaps} completed")
            }
        }
        assertEquals(StartResult.Started, container.engine.start(task))
        val end = runBlocking {
            withTimeout(60_000) {
                container.engine.state.first { it.sessionName == "Gesture test" && !it.status.isActive && it.status != RunStatus.IDLE }
            }
        }
        compose.waitForIdle()
        SystemClock.sleep(300)
        return downs.toList() to end
    }

    /** Rate actually delivered to the view, from the touch-down timestamps. */
    private fun deliveredTps(downs: List<Long>): Double =
        if (downs.size < 2) 0.0 else (downs.size - 1) * 1000.0 / (downs.last() - downs.first())

    private fun gapSummary(downs: List<Long>): String {
        val gaps = downs.zipWithNext { a, b -> b - a }.sorted()
        if (gaps.isEmpty()) return "no gaps"
        return "gaps ms min=${gaps.first()} median=${gaps[gaps.size / 2]} p90=${gaps[(gaps.size * 9) / 10]} max=${gaps.last()}"
    }

    private fun checkBatch(count: Int, tps: Double) {
        val (downs, end) = runBatch(count, tps)
        val stats = end.stats
        val delivered = deliveredTps(downs)
        Log.i(
            "BunTapTest",
            "requested=$tps TPS x$count: received=${downs.size} completed=${stats.completed} dispatched=${stats.dispatched} " +
                "reportedAvg=${stats.averageObservedTps} deliveredAtView=$delivered missed=${stats.missedDeadlines} ${gapSummary(downs)}",
        )
        assertEquals(RunStatus.COMPLETED, end.status)
        assertEquals(count, stats.completed)
        // 1. Every gesture Android reported as completed arrived at the view as a touch.
        assertEquals("every completed gesture arrived as a touch", count, downs.size)
        // 2. The requested rate is a ceiling: never exceeded (small tolerance for clock granularity).
        assertTrue("delivered $delivered TPS exceeds requested $tps", delivered <= tps * 1.05)
        // 3. The reported observed rate matches what the view actually received.
        val reported = stats.averageObservedTps!!
        assertTrue("reported $reported vs delivered $delivered", kotlin.math.abs(reported - delivered) <= delivered * 0.10)
    }

    @Test
    fun tapsAtTwoTpsReachTheView() {
        assumeTrue("Enable the BunTap accessibility service on the test device", waitForService())
        checkBatch(count = 10, tps = 2.0)
    }

    @Test
    fun tapsAtTenTpsReachTheView() {
        assumeTrue("Enable the BunTap accessibility service on the test device", waitForService())
        checkBatch(count = 30, tps = 10.0)
    }

    @Test
    fun tapsAtTwentyTpsAreMeasuredHonestly() {
        assumeTrue("Enable the BunTap accessibility service on the test device", waitForService())
        checkBatch(count = 40, tps = 20.0)
    }

    /** Diagnostic: how long Android takes to report each 10 ms gesture as completed. */
    @Test
    fun gestureCallbackLatencyProbe() {
        assumeTrue("Enable the BunTap accessibility service on the test device", waitForService())
        compose.setContent { Box(Modifier.fillMaxSize()) }
        compose.waitForIdle()
        val g = container.displayMonitor.refresh()
        val latencies = (1..20).map {
            val start = SystemClock.elapsedRealtime()
            val r = runBlocking { container.gestures.tap(g.widthPx / 2, g.heightPx / 2, 10) }
            assertEquals(com.buntap.app.automation.GestureResult.COMPLETED, r)
            SystemClock.elapsedRealtime() - start
        }.sorted()
        Log.i("BunTapTest", "callback latency for a 10 ms tap: min=${latencies.first()} median=${latencies[10]} max=${latencies.last()} ms")
    }
}
