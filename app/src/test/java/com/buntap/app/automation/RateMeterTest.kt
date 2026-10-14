package com.buntap.app.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RateMeterTest {

    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun noRateUntilTwoTaps() {
        val m = RateMeter()
        assertNull(m.averageTps)
        m.record(ms(0))
        assertNull(m.averageTps)
        m.record(ms(200))
        assertEquals(5.0, m.averageTps!!, 1e-9)
    }

    @Test
    fun averageOverConsecutiveIntervals() {
        val m = RateMeter()
        listOf(0L, 100L, 200L, 300L, 400L).forEach { m.record(ms(it)) }
        assertEquals(10.0, m.averageTps!!, 1e-9)
    }

    @Test
    fun gapsAfterBreakContinuityAreIgnored() {
        val m = RateMeter()
        m.record(ms(0))
        m.record(ms(100))
        m.breakContinuity() // e.g. a 5 second wait in a routine
        m.record(ms(5_100))
        m.record(ms(5_200))
        // Two 100 ms intervals; the 5 s gap is not counted.
        assertEquals(10.0, m.averageTps!!, 1e-9)
    }

    @Test
    fun recentWindowTracksLatestRate() {
        val m = RateMeter(window = 3)
        var t = 0L
        repeat(5) { m.record(ms(t)); t += 100 } // 10 TPS
        repeat(4) { m.record(ms(t)); t += 500 } // then 2 TPS
        assertEquals(2.0, m.recentTps!!, 1e-9)
        // Average: 4 intervals of 100 ms + 1 of 100 ms bridging + 3 of 500 ms
        val avg = m.averageTps!!
        assert(avg > 2.0 && avg < 10.0)
    }
}
