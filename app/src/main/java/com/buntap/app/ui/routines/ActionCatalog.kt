package com.buntap.app.ui.routines

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.buntap.app.domain.Ids
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TapAnchor
import com.buntap.app.routines.ContinueIfTargetAction
import com.buntap.app.routines.FindTargetAction
import com.buntap.app.routines.GlobalKey
import com.buntap.app.routines.IfTargetAction
import com.buntap.app.routines.LogAction
import com.buntap.app.routines.NotifyAction
import com.buntap.app.routines.PressKeyAction
import com.buntap.app.routines.RepeatAction
import com.buntap.app.routines.RetryAction
import com.buntap.app.routines.RoutineAction
import com.buntap.app.routines.RoutineDefaults
import com.buntap.app.routines.StopAction
import com.buntap.app.routines.TapPointAction
import com.buntap.app.routines.TapSpec
import com.buntap.app.routines.TapTargetAction
import com.buntap.app.routines.TimeLimitAction
import com.buntap.app.routines.TypeTextAction
import com.buntap.app.routines.WaitAction
import com.buntap.app.routines.WaitForTargetAction
import com.buntap.app.routines.WaitUntilGoneAction
import com.buntap.app.ui.icons.BunIcons

/** Every kind of action the routine builder can add, with a factory for sensible defaults. */
enum class ActionType(val title: String, val description: String, val usesTarget: Boolean = false) {
    WAIT("Wait", "Pause the routine for a fixed time."),
    TAP_POINT("Tap position", "Tap fixed screen coordinates."),
    TAP_BURST("Tap burst", "Tap a position a set number of times at a chosen TPS."),
    FIND_TARGET("Find target", "Look for a visual target; fail if it is not found.", true),
    WAIT_FOR_TARGET("Wait for target", "Wait until a target appears (with timeout).", true),
    WAIT_UNTIL_GONE("Wait until target disappears", "Wait until a target is no longer visible.", true),
    TAP_TARGET("Tap target", "Find a target and tap it.", true),
    TAP_TARGET_POINT("Tap point within target", "Tap a custom point inside a detected target.", true),
    IF_TARGET("If target exists", "Run Then-steps if the target is found, otherwise Else-steps.", true),
    IF_NOT_TARGET("If target does not exist", "Run Then-steps if the target is NOT found.", true),
    CONTINUE_IF_TARGET("Continue if target exists", "Stop or skip the rest of the group if the target is missing.", true),
    REPEAT("Repeat group", "Repeat the steps inside a fixed number of times."),
    RETRY("Retry group", "Run the steps again (up to a limit) if they fail."),
    TIME_LIMIT("Time limit", "Fail the steps inside if they take too long."),
    PRESS_KEY("Press key", "Back, Home, Recents, notifications… (system actions)."),
    TYPE_TEXT("Type text", "Type into the focused text field."),
    NOTIFY("Show notification", "Show a notification with a message."),
    LOG("Log entry", "Write a message to the execution log."),
    STOP("Stop routine", "End the routine with success or failure."),
    ;

    fun create(targetId: String?, geometry: ScreenGeometry, defaults: RoutineDefaults): RoutineAction {
        val id = Ids.newId()
        val t = targetId ?: ""
        val cx = geometry.widthPx / 2
        val cy = geometry.heightPx / 2
        return when (this) {
            WAIT -> WaitAction(id, 1_000)
            TAP_POINT -> TapPointAction(id, cx, cy)
            TAP_BURST -> TapPointAction(id, cx, cy, TapSpec(count = 10, rate = defaults.rate))
            FIND_TARGET -> FindTargetAction(id, t)
            WAIT_FOR_TARGET -> WaitForTargetAction(id, t)
            WAIT_UNTIL_GONE -> WaitUntilGoneAction(id, t)
            TAP_TARGET -> TapTargetAction(id, t)
            TAP_TARGET_POINT -> TapTargetAction(id, t, anchor = TapAnchor(0.5f, 0.25f))
            IF_TARGET -> IfTargetAction(id, t, expectPresent = true)
            IF_NOT_TARGET -> IfTargetAction(id, t, expectPresent = false)
            CONTINUE_IF_TARGET -> ContinueIfTargetAction(id, t)
            REPEAT -> RepeatAction(id, times = 3)
            RETRY -> RetryAction(id, maxAttempts = 3)
            TIME_LIMIT -> TimeLimitAction(id, timeoutMs = 30_000)
            PRESS_KEY -> PressKeyAction(id, GlobalKey.BACK)
            TYPE_TEXT -> TypeTextAction(id, "")
            NOTIFY -> NotifyAction(id, "BunTap", "Routine step reached")
            LOG -> LogAction(id, "Checkpoint")
            STOP -> StopAction(id, success = true, message = "Done")
        }
    }
}

fun iconFor(action: RoutineAction): ImageVector = when (action) {
    is WaitAction -> BunIcons.Timer
    is TapPointAction -> BunIcons.TouchApp
    is FindTargetAction -> Icons.Filled.Search
    is WaitForTargetAction -> BunIcons.Hourglass
    is WaitUntilGoneAction -> BunIcons.Visibility
    is TapTargetAction -> BunIcons.Crosshair
    is PressKeyAction -> BunIcons.Keyboard
    is TypeTextAction -> BunIcons.Keyboard
    is RepeatAction -> BunIcons.Repeat
    is IfTargetAction -> BunIcons.Branch
    is ContinueIfTargetAction -> BunIcons.Branch
    is RetryAction -> BunIcons.Repeat
    is TimeLimitAction -> BunIcons.Timer
    is StopAction -> Icons.Filled.Close
    is NotifyAction -> Icons.Filled.Notifications
    is LogAction -> BunIcons.Notes
}
