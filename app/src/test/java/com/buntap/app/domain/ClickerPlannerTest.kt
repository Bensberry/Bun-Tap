package com.buntap.app.domain

import com.buntap.app.domain.model.ClickerConfig
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.RepeatMode
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.SettingsMode
import com.buntap.app.domain.model.TapPoint
import com.buntap.app.domain.model.TapRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickerPlannerTest {

    private fun point(id: String, count: Int? = null, rate: TapRate? = null, repeat: Int = 1, enabled: Boolean = true) =
        TapPoint(id, "P$id", 100, 200, enabled = enabled, countOverride = count, rateOverride = rate, repeat = repeat)

    @Test
    fun threePointsTimesTenIsThirty() {
        val config = ClickerConfig(points = listOf(point("1"), point("2"), point("3")), tapsPerPoint = 10)
        val plan = ClickerPlanner.plan(config)
        assertEquals(30L, plan.tapsPerPass)
        assertEquals(30, plan.plannedTaps)
        assertTrue(plan.explanation, plan.explanation.contains("30"))
        assertTrue(plan.explanation.contains("each point separately"))
    }

    @Test
    fun globalModeIgnoresOverrides() {
        val config = ClickerConfig(
            points = listOf(point("1", count = 99, rate = TapRate.fromTps(1.0))),
            settingsMode = SettingsMode.GLOBAL,
            tapsPerPoint = 4,
            globalRate = TapRate.fromTps(5.0),
        )
        val ep = ClickerPlanner.effectivePoints(config).single()
        assertEquals(4, ep.count)
        assertEquals(TapRate.fromTps(5.0), ep.rate)
    }

    @Test
    fun perPointModeUsesOverridesWithFallback() {
        val config = ClickerConfig(
            points = listOf(point("1", count = 3, rate = TapRate.fromTps(2.0)), point("2")),
            settingsMode = SettingsMode.PER_POINT,
            tapsPerPoint = 7,
        )
        val eps = ClickerPlanner.effectivePoints(config)
        assertEquals(3, eps[0].count)
        assertEquals(TapRate.fromTps(2.0), eps[0].rate)
        assertEquals(7, eps[1].count)
        assertEquals(10, ClickerPlanner.plan(config).plannedTaps)
    }

    @Test
    fun disabledPointsAreExcludedAndRepeatMultiplies() {
        val config = ClickerConfig(
            points = listOf(point("1", repeat = 3), point("2", enabled = false)),
            tapsPerPoint = 5,
            repeatMode = RepeatMode.FIXED_PASSES,
            passes = 2,
        )
        val plan = ClickerPlanner.plan(config)
        assertEquals(15L, plan.tapsPerPass)
        assertEquals(30, plan.plannedTaps)
    }

    @Test
    fun unlimitedHasNoPlannedTotalUnlessLimited() {
        val config = ClickerConfig(points = listOf(point("1")), repeatMode = RepeatMode.UNTIL_STOPPED)
        val plan = ClickerPlanner.plan(config)
        assertNull(plan.passes)
        assertNull(plan.plannedTaps)
        assertTrue(plan.explanation.contains("until you stop"))

        val limited = ClickerPlanner.plan(config.copy(sessionTapLimit = 25))
        assertEquals(25, limited.plannedTaps)
        assertTrue(limited.limitedBySessionLimit)
    }

    @Test
    fun sessionLimitCapsFiniteRun() {
        val config = ClickerConfig(points = listOf(point("1"), point("2")), tapsPerPoint = 10, sessionTapLimit = 15)
        val plan = ClickerPlanner.plan(config)
        assertEquals(15, plan.plannedTaps)
        assertTrue(plan.limitedBySessionLimit)
        val notLimiting = ClickerPlanner.plan(config.copy(sessionTapLimit = 100))
        assertEquals(20, notLimiting.plannedTaps)
        assertFalse(notLimiting.limitedBySessionLimit)
    }

    @Test
    fun validationFindsProblems() {
        val limits = RateLimits(maxTps = 20.0)
        val geometry = ScreenGeometry(1080, 2400)
        assertTrue(ClickerPlanner.validate(ClickerConfig(), limits, geometry).any { it.contains("at least one") })

        val outside = ClickerConfig(points = listOf(TapPoint("1", "Far", 5000, 10)))
        assertTrue(ClickerPlanner.validate(outside, limits, geometry).any { it.contains("outside the screen") })

        val tooFast = ClickerConfig(points = listOf(point("1")), globalRate = TapRate.fromTps(40.0))
        assertTrue(ClickerPlanner.validate(tooFast, limits, geometry).any { it.contains("Global rate") })

        val badCount = ClickerConfig(points = listOf(point("1")), tapsPerPoint = 0)
        assertTrue(ClickerPlanner.validate(badCount, limits, geometry).any { it.contains("Taps per point") })

        val ok = ClickerConfig(points = listOf(point("1")))
        assertTrue(ClickerPlanner.validate(ok, limits, geometry).isEmpty())
    }
}
