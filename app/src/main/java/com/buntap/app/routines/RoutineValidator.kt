package com.buntap.app.routines

import com.buntap.app.domain.CoordinateValidator
import com.buntap.app.domain.RateValidator
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TapRate

/**
 * Checks a routine before it is saved, imported, or run. Every loop and retry is bounded,
 * every number is range-checked, and every target reference must resolve.
 */
object RoutineValidator {

    const val MAX_NAME_LENGTH = 80
    const val MAX_DESCRIPTION_LENGTH = 500
    const val MAX_DEPTH = 6
    const val MAX_ACTIONS = 500
    const val MAX_REPEAT_TIMES = 100_000
    const val MAX_RETRY_ATTEMPTS = 20
    const val MAX_WAIT_MS = 3_600_000L
    const val MAX_TIMEOUT_MS = 3_600_000L
    const val MAX_TAP_COUNT = 100_000
    const val MAX_TEXT_LENGTH = 2_000
    const val MAX_OFFSET = 10_000

    data class Issue(val actionId: String?, val message: String)

    fun validate(
        name: String,
        body: RoutineBody,
        knownTargetIds: Set<String>,
        limits: RateLimits,
        geometry: ScreenGeometry?,
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        if (name.isBlank()) issues += Issue(null, "Give the routine a name.")
        if (name.length > MAX_NAME_LENGTH) issues += Issue(null, "Name is longer than $MAX_NAME_LENGTH characters.")
        if (body.actions.isEmpty()) issues += Issue(null, "Add at least one action.")

        val all = RoutineTree.all(body.actions).toList()
        if (all.size > MAX_ACTIONS) issues += Issue(null, "Too many actions (${all.size}); the maximum is $MAX_ACTIONS.")
        val depth = RoutineTree.depth(body.actions)
        if (depth > MAX_DEPTH) issues += Issue(null, "Groups are nested $depth levels deep; the maximum is $MAX_DEPTH.")
        val duplicates = all.groupBy { it.id }.filter { it.value.size > 1 }.keys
        if (duplicates.isNotEmpty()) issues += Issue(null, "Duplicate action IDs: ${duplicates.joinToString()}.")
        if (all.any { it.id.isBlank() }) issues += Issue(null, "Every action needs an ID.")

        checkRate(body.defaults.rate, limits)?.let { issues += Issue(null, "Default rate: $it") }
        if (body.defaults.tapCount !in 1..MAX_TAP_COUNT) issues += Issue(null, "Default tap count must be 1–$MAX_TAP_COUNT.")
        if (body.defaults.timeoutMs !in 0..MAX_TIMEOUT_MS) issues += Issue(null, "Default timeout must be 0–${MAX_TIMEOUT_MS / 1000} s.")
        body.maxDurationMs?.let { if (it < 1_000) issues += Issue(null, "Maximum duration must be at least 1 s.") }
        body.maxTotalTaps?.let { if (it < 1) issues += Issue(null, "Maximum total taps must be at least 1.") }

        for (a in all) issues += validateAction(a, body.defaults, knownTargetIds, limits, geometry)
        return issues
    }

    private fun checkRate(rate: TapRate, limits: RateLimits): String? = RateValidator.check(rate, limits)

    private fun validateAction(
        a: RoutineAction,
        defaults: RoutineDefaults,
        targets: Set<String>,
        limits: RateLimits,
        geometry: ScreenGeometry?,
    ): List<Issue> {
        val out = ArrayList<Issue>()
        fun err(msg: String) {
            out += Issue(a.id, "${RoutineDescriber.title(a)}: $msg")
        }
        fun checkTimeout(ms: Long?) {
            if (ms != null && ms !in 0..MAX_TIMEOUT_MS) err("timeout must be 0–${MAX_TIMEOUT_MS / 1000} s")
        }
        fun checkTarget(id: String) {
            if (id.isBlank()) err("choose a visual target") else if (id !in targets) err("refers to a target that does not exist")
        }
        fun checkTaps(spec: TapSpec) {
            spec.count?.let { if (it !in 1..MAX_TAP_COUNT) err("tap count must be 1–$MAX_TAP_COUNT") }
            spec.rate?.let { r -> checkRate(r, limits)?.let { err(it) } }
        }
        fun checkDelay(ms: Long, label: String) {
            if (ms !in 0..MAX_WAIT_MS) err("$label must be 0–${MAX_WAIT_MS / 1000} s")
        }

        if (a.note.length > MAX_DESCRIPTION_LENGTH) err("note is too long")
        when (a) {
            is WaitAction -> if (a.durationMs !in 0..MAX_WAIT_MS) err("duration must be 0–${MAX_WAIT_MS / 1000} s")
            is TapPointAction -> {
                CoordinateValidator.check(a.x, a.y, geometry)?.let { err(it) }
                checkTaps(a.taps)
                checkDelay(a.preDelayMs, "delay before")
                checkDelay(a.postDelayMs, "delay after")
                a.timeoutMs?.let { if (it !in 100..MAX_TIMEOUT_MS) err("timeout must be 100 ms–1 h") }
            }
            is FindTargetAction -> { checkTarget(a.targetId); checkTimeout(a.timeoutMs) }
            is WaitForTargetAction -> { checkTarget(a.targetId); checkTimeout(a.timeoutMs) }
            is WaitUntilGoneAction -> { checkTarget(a.targetId); checkTimeout(a.timeoutMs) }
            is TapTargetAction -> {
                checkTarget(a.targetId)
                checkTimeout(a.timeoutMs)
                checkTaps(a.taps)
                checkDelay(a.preDelayMs, "delay before")
                checkDelay(a.postDelayMs, "delay after")
                a.anchor?.let { if (it.fx !in 0f..1f || it.fy !in 0f..1f) err("anchor must be within the target (0–100%)") }
                if (a.offsetX !in -MAX_OFFSET..MAX_OFFSET || a.offsetY !in -MAX_OFFSET..MAX_OFFSET) err("offset is too large")
            }
            is PressKeyAction -> Unit
            is TypeTextAction -> {
                if (a.text.isEmpty() && !a.append) err("enter the text to type")
                if (a.text.length > MAX_TEXT_LENGTH) err("text is longer than $MAX_TEXT_LENGTH characters")
            }
            is RepeatAction -> {
                if (a.times !in 1..MAX_REPEAT_TIMES) err("repeat count must be 1–$MAX_REPEAT_TIMES")
                if (a.actions.isEmpty()) err("add at least one step to repeat")
            }
            is IfTargetAction -> {
                checkTarget(a.targetId)
                checkTimeout(a.timeoutMs)
                if (a.thenActions.isEmpty() && a.elseActions.isEmpty()) err("add steps to Then or Else")
            }
            is ContinueIfTargetAction -> { checkTarget(a.targetId); checkTimeout(a.timeoutMs) }
            is RetryAction -> {
                if (a.maxAttempts !in 1..MAX_RETRY_ATTEMPTS) err("attempts must be 1–$MAX_RETRY_ATTEMPTS")
                checkDelay(a.delayBetweenMs, "delay between attempts")
                if (a.actions.isEmpty()) err("add at least one step to retry")
            }
            is TimeLimitAction -> {
                if (a.timeoutMs !in 100..MAX_TIMEOUT_MS) err("time limit must be 100 ms–1 h")
                if (a.actions.isEmpty()) err("add at least one step")
            }
            is StopAction -> if (a.message.length > MAX_DESCRIPTION_LENGTH) err("message is too long")
            is NotifyAction -> {
                if (a.title.isBlank() && a.message.isBlank()) err("enter a title or message")
                if (a.title.length > MAX_NAME_LENGTH || a.message.length > MAX_DESCRIPTION_LENGTH) err("text is too long")
            }
            is LogAction -> {
                if (a.message.isBlank()) err("enter a message")
                if (a.message.length > MAX_DESCRIPTION_LENGTH) err("message is too long")
            }
        }
        return out
    }

    /** True when the routine needs screen capture (it uses visual targets). */
    fun usesTargets(body: RoutineBody): Boolean =
        RoutineTree.all(body.actions).any { it.enabled && RoutineTree.targetIdOf(it) != null }

    /** True when the routine needs the accessibility service. */
    fun usesAccessibility(body: RoutineBody): Boolean = RoutineTree.all(body.actions).any {
        it.enabled && (it is TapPointAction || it is TapTargetAction || it is PressKeyAction || it is TypeTextAction)
    }

    /** True when any enabled action uses fixed screen coordinates. */
    fun usesFixedCoordinates(body: RoutineBody): Boolean =
        RoutineTree.all(body.actions).any { it.enabled && it is TapPointAction }
}
