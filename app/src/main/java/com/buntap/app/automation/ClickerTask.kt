package com.buntap.app.automation

import com.buntap.app.domain.ClickerPlanner
import com.buntap.app.domain.RateValidator
import com.buntap.app.domain.model.ClickerConfig
import com.buntap.app.domain.model.EventType
import com.buntap.app.domain.model.RepeatMode
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.domain.model.StopReason

/**
 * The standard auto-clicker: visits each enabled point in order, taps it the configured
 * number of times at the configured rate, and repeats the sequence according to the
 * repeat mode. All timing goes through [ExecutionContext.tapBatch].
 */
class ClickerTask(private val config: ClickerConfig) : AutomationTask {

    private val plan = ClickerPlanner.plan(config)

    override val name: String = "Auto clicker"
    override val kind: SessionKind = SessionKind.CLICKER
    override val startDelayMs: Long = config.startDelayMs
    override val maxDurationMs: Long? = config.maxDurationMs
    override val tapLimit: Int? = config.sessionTapLimit
    override val plannedTaps: Int? = plan.plannedTaps

    override suspend fun run(ctx: ExecutionContext): TaskOutcome {
        val points = plan.points
        if (points.isEmpty()) return TaskOutcome(false, "No enabled tap points")
        ctx.requireGeometry(config.reference)
        ctx.log(EventType.INFO, plan.explanation)

        var pass = 0
        while (true) {
            pass++
            for (ep in points) {
                ctx.checkpoint()
                // Never tap coordinates picked for a different screen layout.
                ctx.requireGeometry(config.reference)
                for (visit in 1..ep.repeat) {
                    val passLabel = if (plan.passes == 1) "" else "Pass $pass · "
                    val visitLabel = if (ep.repeat > 1) " (visit $visit/${ep.repeat})" else ""
                    ctx.setActivity(
                        "$passLabel${ep.point.name}$visitLabel: ${ep.count} taps at " +
                            "${RateValidator.formatTps(ep.rate.tps)} TPS",
                    )
                    val result = ctx.tapBatch(
                        TapBatchRequest(
                            label = ep.point.name,
                            x = ep.point.x,
                            y = ep.point.y,
                            count = ep.count,
                            rate = ep.rate,
                            preDelayMs = ep.point.preDelayMs,
                            postDelayMs = ep.point.postDelayMs,
                            timeoutMs = ep.point.timeoutMs,
                            reference = config.reference,
                        ),
                    )
                    when (result.outcome) {
                        BatchOutcome.COMPLETED -> Unit
                        BatchOutcome.TIMED_OUT -> ctx.log(
                            EventType.TIMEOUT,
                            "${ep.point.name} reached its ${ep.point.timeoutMs} ms timeout after " +
                                "${result.completedTaps} taps; moving on",
                        )
                        BatchOutcome.FAILED -> return TaskOutcome(
                            success = false,
                            message = result.message ?: "Taps on ${ep.point.name} failed",
                            reason = StopReason.ERROR,
                        )
                    }
                }
            }
            when (config.repeatMode) {
                RepeatMode.ONE_PASS -> break
                RepeatMode.FIXED_PASSES -> if (pass >= config.passes) break
                RepeatMode.UNTIL_STOPPED -> Unit
            }
        }
        return TaskOutcome(true, "Completed $pass pass${if (pass == 1) "" else "es"}")
    }
}
