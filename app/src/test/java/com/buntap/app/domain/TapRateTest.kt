package com.buntap.app.domain

import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.TapRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapRateTest {

    @Test
    fun tpsToIntervalExamplesFromSpec() {
        assertEquals(1000.0, TapRate.fromTps(1.0).intervalMs, 0.0)
        assertEquals(500.0, TapRate.fromTps(2.0).intervalMs, 0.0)
        assertEquals(200.0, TapRate.fromTps(5.0).intervalMs, 0.0)
        assertEquals(100.0, TapRate.fromTps(10.0).intervalMs, 0.0)
        assertEquals(50.0, TapRate.fromTps(20.0).intervalMs, 0.0)
    }

    @Test
    fun intervalToTps() {
        assertEquals(1.0, TapRate.fromIntervalMs(1000.0).tps, 1e-12)
        assertEquals(4.0, TapRate.fromIntervalMs(250.0).tps, 1e-12)
        assertEquals(20.0, TapRate.fromIntervalMs(50.0).tps, 1e-12)
    }

    @Test
    fun repeatedConversionDoesNotDrift() {
        var rate = TapRate.fromTps(3.0)
        repeat(100) {
            rate = TapRate.fromIntervalMs(rate.intervalMs)
            rate = TapRate.fromTps(rate.tps)
        }
        assertEquals(333_333L, rate.intervalMicros)
        assertEquals("3", RateValidator.formatTps(rate.tps))
        assertEquals("333.333", RateValidator.formatMs(rate.intervalMs))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroTpsRejected() {
        TapRate.fromTps(0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeIntervalRejected() {
        TapRate.fromIntervalMs(-5.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nanRejected() {
        TapRate.fromTps(Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroMicrosRejected() {
        TapRate(0)
    }

    @Test
    fun formatting() {
        assertEquals("5", RateValidator.formatTps(5.0))
        assertEquals("0.5", RateValidator.formatTps(0.5))
        assertEquals("3.33", RateValidator.formatTps(10.0 / 3.0))
        assertEquals("200", RateValidator.formatMs(200.0))
        assertEquals("66.667", RateValidator.formatMs(1000.0 / 15.0))
    }

    @Test
    fun rateLimitsDerivedValues() {
        val limits = RateLimits(maxTps = 20.0, pressDurationMs = 10)
        assertEquals(50.0, limits.minIntervalMs, 1e-9)
        assertEquals(10_000.0, limits.maxIntervalMs, 1e-9)
        // A long press duration becomes the binding constraint.
        val slow = RateLimits(maxTps = 50.0, pressDurationMs = 40)
        assertEquals(45.0, slow.minIntervalMs, 1e-9)
        assertTrue(slow.effectiveMaxTps < 50.0)
        assertNull(RateValidator.check(TapRate.fromIntervalMs(45.0), slow))
    }
}
