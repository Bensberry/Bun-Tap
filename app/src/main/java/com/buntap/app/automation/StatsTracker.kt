package com.buntap.app.automation

import com.buntap.app.domain.model.StatsSnapshot

/**
 * Thread-safe counters for one session. The session coroutine writes; the UI publisher
 * reads snapshots from another thread.
 */
class StatsTracker(plannedTaps: Int?) {
    private val lock = Any()
    private var counters = StatsSnapshot(plannedTaps = plannedTaps)
    private val rateMeter = RateMeter()

    fun update(block: (StatsSnapshot) -> StatsSnapshot) {
        synchronized(lock) { counters = block(counters) }
    }

    /** Records the result of one gesture; [timestampNs] is when Android reported back. */
    fun recordGesture(result: GestureResult, timestampNs: Long) {
        synchronized(lock) {
            val c = counters
            counters = c.copy(
                dispatched = c.dispatched + if (result.wasDispatched) 1 else 0,
                completed = c.completed + if (result == GestureResult.COMPLETED) 1 else 0,
                cancelled = c.cancelled + if (result == GestureResult.CANCELLED) 1 else 0,
                rejected = c.rejected + if (result == GestureResult.REJECTED) 1 else 0,
                timedOut = c.timedOut + if (result == GestureResult.TIMED_OUT) 1 else 0,
            )
            if (result == GestureResult.COMPLETED) rateMeter.record(timestampNs)
        }
    }

    fun breakContinuity() {
        synchronized(lock) { rateMeter.breakContinuity() }
    }

    val completed: Int get() = synchronized(lock) { counters.completed }
    val dispatched: Int get() = synchronized(lock) { counters.dispatched }

    fun snapshot(activeElapsedMs: Long): StatsSnapshot = synchronized(lock) {
        counters.copy(
            recentObservedTps = rateMeter.recentTps,
            averageObservedTps = rateMeter.averageTps,
            activeElapsedMs = activeElapsedMs,
        )
    }
}
