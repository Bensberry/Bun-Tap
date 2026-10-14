package com.buntap.app.domain

import com.buntap.app.domain.model.GeometryCompatibility
import com.buntap.app.domain.model.GeometryRules
import com.buntap.app.domain.model.PixelPoint
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.ScreenGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    private val limits = RateLimits(maxTps = 20.0, pressDurationMs = 10)

    @Test
    fun parsesValidTps() {
        val v = RateValidator.parseTps("10", limits)
        assertEquals(100_000L, v.valueOrNull!!.intervalMicros)
    }

    @Test
    fun acceptsCommaDecimalSeparator() {
        val v = RateValidator.parseTps("2,5", limits)
        assertEquals(400_000L, v.valueOrNull!!.intervalMicros)
    }

    @Test
    fun parsesInterval() {
        val v = RateValidator.parseIntervalMs("250", limits)
        assertEquals(4.0, v.valueOrNull!!.tps, 1e-9)
    }

    @Test
    fun rejectsInvalidInput() {
        assertNotNull(RateValidator.parseTps("", limits).errorOrNull)
        assertNotNull(RateValidator.parseTps("abc", limits).errorOrNull)
        assertNotNull(RateValidator.parseTps("0", limits).errorOrNull)
        assertNotNull(RateValidator.parseTps("-3", limits).errorOrNull)
        assertNotNull(RateValidator.parseTps("Infinity", limits).errorOrNull)
        assertNotNull(RateValidator.parseIntervalMs("0", limits).errorOrNull)
        assertNotNull(RateValidator.parseIntervalMs("NaN", limits).errorOrNull)
    }

    @Test
    fun enforcesMaximumAndMinimum() {
        val tooFast = RateValidator.parseTps("25", limits).errorOrNull
        assertTrue(tooFast!!.contains("Maximum is 20 TPS"))
        assertNull(RateValidator.parseTps("20", limits).errorOrNull)
        assertNull(RateValidator.parseIntervalMs("50", limits).errorOrNull)
        assertNotNull(RateValidator.parseIntervalMs("49.9", limits).errorOrNull)
        val tooSlow = RateValidator.parseTps("0.05", limits).errorOrNull
        assertTrue(tooSlow!!.contains("Minimum"))
        assertNull(RateValidator.parseTps("0.1", limits).errorOrNull)
    }

    @Test
    fun pressDurationLimitsInterval() {
        val longPress = RateLimits(maxTps = 50.0, pressDurationMs = 40)
        val msg = RateValidator.parseIntervalMs("30", longPress).errorOrNull
        assertTrue(msg!!.contains("held for 40 ms"))
    }

    @Test
    fun integerAndMillisValidation() {
        assertEquals(5, NumberInput.int("5", 1, 10, "Count").valueOrNull)
        assertNotNull(NumberInput.int("0", 1, 10, "Count").errorOrNull)
        assertNotNull(NumberInput.int("11", 1, 10, "Count").errorOrNull)
        assertNotNull(NumberInput.int("1.5", 1, 10, "Count").errorOrNull)
        assertEquals(500L, NumberInput.millis("500", 0, 1000, "Delay").valueOrNull)
        assertNotNull(NumberInput.millis("-1", 0, 1000, "Delay").errorOrNull)
    }

    @Test
    fun coordinateValidation() {
        val g = ScreenGeometry(1080, 2400)
        assertNull(CoordinateValidator.check(0, 0, g))
        assertNull(CoordinateValidator.check(1079, 2399, g))
        assertNotNull(CoordinateValidator.check(1080, 10, g))
        assertNotNull(CoordinateValidator.check(10, 2400, g))
        assertNotNull(CoordinateValidator.check(-1, 10, null))
        assertNull(CoordinateValidator.check(5000, 5000, null))
    }

    @Test
    fun geometryCompatibility() {
        val portrait = ScreenGeometry(1080, 2400, 0)
        val landscape = ScreenGeometry(2400, 1080, 1)
        val smaller = ScreenGeometry(720, 1600, 0)
        assertEquals(GeometryCompatibility.Same, GeometryRules.compare(portrait, portrait.copy()))
        assertEquals(GeometryCompatibility.Unknown, GeometryRules.compare(null, portrait))
        assertTrue(GeometryRules.compare(portrait, landscape) is GeometryCompatibility.Incompatible)
        assertTrue(GeometryRules.compare(portrait, smaller) is GeometryCompatibility.Scalable)
        assertEquals(PixelPoint(360, 800), GeometryRules.scalePoint(540, 1200, portrait, smaller))
        assertEquals(PixelPoint(719, 1599), GeometryRules.scalePoint(1079, 2399, portrait, smaller))
        assertNotNull(GeometryRules.explain(GeometryRules.compare(portrait, landscape)))
    }
}
