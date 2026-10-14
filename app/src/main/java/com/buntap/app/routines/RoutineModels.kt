package com.buntap.app.routines

import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TapAnchor
import com.buntap.app.domain.model.TapRate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Default values that individual actions inherit unless they override them. */
@Serializable
data class RoutineDefaults(
    val rate: TapRate = TapRate.DEFAULT,
    val tapCount: Int = 1,
    /** Default time to look for a target. */
    val timeoutMs: Long = 10_000,
)

/** Tap count and rate for an action. Null fields fall back to [RoutineDefaults]. */
@Serializable
data class TapSpec(
    val count: Int? = null,
    val rate: TapRate? = null,
)

/** What happens when an action fails. */
@Serializable
enum class FailurePolicy {
    /** Stop the routine (or let an enclosing Retry group try again). */
    STOP_ROUTINE,

    /** Mark the action failed and continue with the next one. */
    CONTINUE,
}

/**
 * Keys BunTap can press. Android does not let apps inject arbitrary key events, so these
 * map to accessibility global actions (and IME "Enter" on Android 11+).
 */
@Serializable
enum class GlobalKey(val label: String, val minSdk: Int) {
    BACK("Back", 26),
    HOME("Home", 26),
    RECENTS("Recent apps", 26),
    NOTIFICATIONS("Notification shade", 26),
    QUICK_SETTINGS("Quick settings", 26),
    LOCK_SCREEN("Lock screen", 28),
    ENTER("Enter (IME action on focused field)", 30),
}

/** What "Continue if target exists" does when the target is missing. */
@Serializable
enum class MissingTargetBehavior(val label: String) {
    STOP_SUCCESS("Stop routine (success)"),
    STOP_FAILURE("Stop routine (failure)"),
    SKIP_REST_OF_GROUP("Skip the rest of this group"),
}

/**
 * One step of a routine. Every action has a stable [id], can be disabled, carries an optional
 * [note], and has a [onFailure] policy. Group actions contain nested action lists.
 *
 * The JSON form uses a `"type"` discriminator (e.g. `"type": "wait"`); see [RoutineJson].
 */
@Serializable
sealed interface RoutineAction {
    val id: String
    val enabled: Boolean
    val note: String
    val onFailure: FailurePolicy
}

@Serializable
@SerialName("wait")
data class WaitAction(
    override val id: String,
    val durationMs: Long = 1_000,
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

/** Taps fixed screen coordinates (also used for "tap burst: N taps at TPS"). */
@Serializable
@SerialName("tap_point")
data class TapPointAction(
    override val id: String,
    val x: Int,
    val y: Int,
    val taps: TapSpec = TapSpec(),
    val preDelayMs: Long = 0,
    val postDelayMs: Long = 0,
    /** Active-time limit for the taps; the action fails if exceeded. */
    val timeoutMs: Long? = null,
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

/** Searches for a target once (or until [timeoutMs]); fails if not found. */
@Serializable
@SerialName("find_target")
data class FindTargetAction(
    override val id: String,
    val targetId: String,
    /** Null = routine default. 0 = a single scan. */
    val timeoutMs: Long? = null,
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

@Serializable
@SerialName("wait_for_target")
data class WaitForTargetAction(
    override val id: String,
    val targetId: String,
    val timeoutMs: Long? = null,
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

@Serializable
@SerialName("wait_until_gone")
data class WaitUntilGoneAction(
    override val id: String,
    val targetId: String,
    val timeoutMs: Long? = null,
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

/**
 * Taps a detected target. With [anchor] = null the target's own tap settings are used;
 * otherwise the tap goes to a custom point inside the match plus [offsetX]/[offsetY].
 */
@Serializable
@SerialName("tap_target")
data class TapTargetAction(
    override val id: String,
    val targetId: String,
    val taps: TapSpec = TapSpec(),
    val anchor: TapAnchor? = null,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    /** Reuse the detection from a previous Find/Wait/If step instead of searching again. */
    val useLastDetection: Boolean = false,
    /** How long to search for the target. Null = routine default. */
    val timeoutMs: Long? = null,
    val preDelayMs: Long = 0,
    val postDelayMs: Long = 0,
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

@Serializable
@SerialName("press_key")
data class PressKeyAction(
    override val id: String,
    val key: GlobalKey = GlobalKey.BACK,
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

/**
 * Types text into the currently focused editable field using the accessibility
 * ACTION_SET_TEXT mechanism. Do not store passwords here: routines are stored and exported
 * as plain text.
 */
@Serializable
@SerialName("type_text")
data class TypeTextAction(
    override val id: String,
    val text: String = "",
    /** Append to the field's current text instead of replacing it. */
    val append: Boolean = false,
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

/** Repeats a group of actions a fixed number of times. */
@Serializable
@SerialName("repeat")
data class RepeatAction(
    override val id: String,
    val times: Int = 2,
    val actions: List<RoutineAction> = emptyList(),
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

/** Branches on whether a target is (or is not) visible within [timeoutMs]. */
@Serializable
@SerialName("if_target")
data class IfTargetAction(
    override val id: String,
    val targetId: String,
    /** true: "if target exists"; false: "if target does not exist". */
    val expectPresent: Boolean = true,
    /** How long to look for the target before deciding. Null = routine default. */
    val timeoutMs: Long? = null,
    val thenActions: List<RoutineAction> = emptyList(),
    val elseActions: List<RoutineAction> = emptyList(),
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

/** Continues only if the target exists; otherwise behaves as [whenMissing]. */
@Serializable
@SerialName("continue_if_target")
data class ContinueIfTargetAction(
    override val id: String,
    val targetId: String,
    val timeoutMs: Long? = null,
    val whenMissing: MissingTargetBehavior = MissingTargetBehavior.STOP_SUCCESS,
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

/** Runs a group again (up to [maxAttempts] times in total) when it fails. */
@Serializable
@SerialName("retry")
data class RetryAction(
    override val id: String,
    val maxAttempts: Int = 3,
    val delayBetweenMs: Long = 500,
    val actions: List<RoutineAction> = emptyList(),
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

/** Runs a group with a time limit; when it expires the group fails ("stop when a timeout expires"). */
@Serializable
@SerialName("time_limit")
data class TimeLimitAction(
    override val id: String,
    val timeoutMs: Long = 30_000,
    val actions: List<RoutineAction> = emptyList(),
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

@Serializable
@SerialName("stop")
data class StopAction(
    override val id: String,
    val success: Boolean = true,
    val message: String = "",
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.STOP_ROUTINE,
) : RoutineAction

@Serializable
@SerialName("notify")
data class NotifyAction(
    override val id: String,
    val title: String = "BunTap",
    val message: String = "",
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.CONTINUE,
) : RoutineAction

@Serializable
@SerialName("log")
data class LogAction(
    override val id: String,
    val message: String = "",
    override val enabled: Boolean = true,
    override val note: String = "",
    override val onFailure: FailurePolicy = FailurePolicy.CONTINUE,
) : RoutineAction

/** Everything about a routine except its library metadata. Stored as JSON. */
@Serializable
data class RoutineBody(
    val defaults: RoutineDefaults = RoutineDefaults(),
    val actions: List<RoutineAction> = emptyList(),
    /** Active-time limit for one run. Null = only the global safety limit. */
    val maxDurationMs: Long? = null,
    /** Successful-tap limit for one run. Null = only the global safety limit. */
    val maxTotalTaps: Int? = null,
    /** Screen on which fixed coordinates were chosen. */
    val reference: ScreenGeometry? = null,
)

/** A routine in the library. */
data class Routine(
    val id: String,
    val name: String,
    val description: String,
    val body: RoutineBody,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long? = null,
    val lastRunStatus: String? = null,
    /** Imported from a file and not yet confirmed by the user. */
    val needsReview: Boolean = false,
)
