package com.buntap.app.automation

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A monotonic clock in nanoseconds. Monotonic time never jumps when the user or network
 * changes the wall clock, so it is the right tool for measuring short intervals.
 */
fun interface MonotonicClock {
    fun nanos(): Long
}

fun MonotonicClock.millis(): Long = nanos() / 1_000_000L

/** Production clock: time since boot, including deep sleep. */
object SystemMonotonicClock : MonotonicClock {
    override fun nanos(): Long = SystemClock.elapsedRealtimeNanos()
}

internal fun nanosToMillisCeil(ns: Long): Long = (ns + 999_999L) / 1_000_000L

/**
 * Pause/resume switch shared by everything in one session. Automation code calls
 * [awaitResumed] at safe points; it suspends (without busy-waiting) while paused.
 */
class PauseGate {
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    val isPaused: Boolean get() = _paused.value

    fun pause() {
        _paused.value = true
    }

    fun resume() {
        _paused.value = false
    }

    /** Suspends while paused. Returns true if it actually had to wait. */
    suspend fun awaitResumed(): Boolean {
        if (!_paused.value) return false
        _paused.first { !it }
        return true
    }
}

/** Result of an operation with a time limit. */
sealed interface Timed<out T> {
    data class Done<T>(val value: T) : Timed<T>
    data object TimedOut : Timed<Nothing>
}

/**
 * Delays and timeouts measured in *active* time: time spent paused does not count.
 * This lets a routine be paused in the middle of a 10-second wait without the wait
 * (or a surrounding timeout) expiring while paused.
 */
class ActiveTimer(private val clock: MonotonicClock, private val gate: PauseGate) {

    /**
     * Waits for [durationNs] of unpaused time. Cancellable at any point.
     * Returns true if a pause happened during the wait.
     */
    suspend fun delayActive(durationNs: Long): Boolean {
        var remaining = durationNs
        var interrupted = false
        while (true) {
            if (gate.awaitResumed()) interrupted = true
            if (remaining <= 0L) return interrupted
            val start = clock.nanos()
            val pausedMidway = withTimeoutOrNull(nanosToMillisCeil(remaining)) {
                gate.paused.first { it }
            } != null
            remaining -= clock.nanos() - start
            if (!pausedMidway) return interrupted
            interrupted = true
        }
    }

    /**
     * Runs [block] with a limit of [timeoutMs] active milliseconds. When the limit expires the
     * block is cancelled and [Timed.TimedOut] is returned. Exceptions from [block] propagate.
     */
    suspend fun <T> withActiveTimeout(timeoutMs: Long, block: suspend CoroutineScope.() -> T): Timed<T> =
        coroutineScope {
            val fired = AtomicBoolean(false)
            val work = async { block() }
            val watchdog = launch {
                delayActive(timeoutMs * 1_000_000L)
                fired.set(true)
                work.cancel()
            }
            try {
                Timed.Done(work.await())
            } catch (e: CancellationException) {
                if (fired.get()) Timed.TimedOut else throw e
            } finally {
                watchdog.cancel()
            }
        }
}

/**
 * Measures the observed tapping rate from real gesture completion timestamps.
 *
 * Only intervals between *consecutive* taps are counted. [breakContinuity] is called whenever
 * a deliberate gap occurs (pause, wait, pre/post delay, screen scan) so those gaps do not drag
 * the average down: observed TPS = number of intervals / total interval time.
 */
class RateMeter(private val window: Int = 20) {
    private var lastNs: Long? = null
    private var intervalCount = 0L
    private var intervalSumNs = 0L
    private val recent = ArrayDeque<Long>()

    fun record(timestampNs: Long) {
        val last = lastNs
        if (last != null) {
            val d = timestampNs - last
            if (d > 0) {
                intervalCount++
                intervalSumNs += d
                recent.addLast(d)
                if (recent.size > window) recent.removeFirst()
            }
        }
        lastNs = timestampNs
    }

    fun breakContinuity() {
        lastNs = null
        recent.clear()
    }

    /** Average over the whole session, or null before two consecutive taps completed. */
    val averageTps: Double?
        get() = if (intervalCount == 0L || intervalSumNs <= 0L) null else intervalCount * 1e9 / intervalSumNs

    /** Average over the most recent consecutive taps (live display). */
    val recentTps: Double?
        get() {
            if (recent.isEmpty()) return null
            val sum = recent.sum()
            return if (sum <= 0L) null else recent.size * 1e9 / sum
        }
}
