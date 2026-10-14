package com.buntap.app.routines

import com.buntap.app.domain.RateValidator
import java.util.Locale

/** Human-readable names and one-line summaries of routine actions (UI and logs). */
object RoutineDescriber {

    fun title(action: RoutineAction): String = when (action) {
        is WaitAction -> "Wait"
        is TapPointAction -> "Tap position"
        is FindTargetAction -> "Find target"
        is WaitForTargetAction -> "Wait for target"
        is WaitUntilGoneAction -> "Wait until target disappears"
        is TapTargetAction -> if (action.anchor == null) "Tap target" else "Tap point within target"
        is PressKeyAction -> "Press key"
        is TypeTextAction -> "Type text"
        is RepeatAction -> "Repeat group"
        is IfTargetAction -> if (action.expectPresent) "If target exists" else "If target does not exist"
        is ContinueIfTargetAction -> "Continue if target exists"
        is RetryAction -> "Retry group"
        is TimeLimitAction -> "Time limit"
        is StopAction -> "Stop routine"
        is NotifyAction -> "Show notification"
        is LogAction -> "Log entry"
    }

    fun summary(action: RoutineAction, defaults: RoutineDefaults, targetName: (String) -> String): String = when (action) {
        is WaitAction -> "Wait ${seconds(action.durationMs)}"
        is TapPointAction -> "(${action.x}, ${action.y}) ${taps(action.taps, defaults)}" +
            (action.timeoutMs?.let { ", timeout ${seconds(it)}" } ?: "")
        is FindTargetAction -> "Look for “${targetName(action.targetId)}” for ${timeout(action.timeoutMs, defaults)}"
        is WaitForTargetAction -> "Wait up to ${timeout(action.timeoutMs, defaults)} for “${targetName(action.targetId)}”"
        is WaitUntilGoneAction -> "Wait up to ${timeout(action.timeoutMs, defaults)} until “${targetName(action.targetId)}” is gone"
        is TapTargetAction -> buildString {
            append("“${targetName(action.targetId)}” ${taps(action.taps, defaults)}")
            action.anchor?.let { append(String.format(Locale.US, " at (%.0f%%, %.0f%%)", it.fx * 100, it.fy * 100)) }
            if (action.offsetX != 0 || action.offsetY != 0) append(" offset (${action.offsetX}, ${action.offsetY})")
            if (action.useLastDetection) append(", reuses last detection")
        }
        is PressKeyAction -> action.key.label
        is TypeTextAction -> "${action.text.length} characters${if (action.append) " (append)" else ""}"
        is RepeatAction -> "${action.times}× (${action.actions.size} step${plural(action.actions.size)})"
        is IfTargetAction -> "“${targetName(action.targetId)}” checked for ${timeout(action.timeoutMs, defaults)}"
        is ContinueIfTargetAction -> "“${targetName(action.targetId)}”; otherwise ${action.whenMissing.label.lowercase()}"
        is RetryAction -> "Up to ${action.maxAttempts} attempts, ${seconds(action.delayBetweenMs)} apart"
        is TimeLimitAction -> "Fail the group after ${seconds(action.timeoutMs)}"
        is StopAction -> (if (action.success) "Success" else "Failure") +
            (if (action.message.isNotBlank()) ": ${action.message}" else "")
        is NotifyAction -> "${action.title}: ${action.message}"
        is LogAction -> action.message
    }

    fun taps(spec: TapSpec, defaults: RoutineDefaults): String {
        val count = spec.count ?: defaults.tapCount
        val rate = spec.rate ?: defaults.rate
        return "× $count at ${RateValidator.formatTps(rate.tps)} TPS"
    }

    private fun timeout(ms: Long?, defaults: RoutineDefaults): String {
        val value = ms ?: defaults.timeoutMs
        return if (value <= 0) "one scan" else seconds(value)
    }

    fun seconds(ms: Long): String =
        if (ms < 1000) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1000.0).replace(".0 s", " s")

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
