package com.buntap.app.ui.routines

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.buntap.app.domain.CoordinateValidator
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TapAnchor
import com.buntap.app.domain.model.VisualTarget
import com.buntap.app.routines.ContinueIfTargetAction
import com.buntap.app.routines.FailurePolicy
import com.buntap.app.routines.FindTargetAction
import com.buntap.app.routines.GlobalKey
import com.buntap.app.routines.IfTargetAction
import com.buntap.app.routines.LogAction
import com.buntap.app.routines.MissingTargetBehavior
import com.buntap.app.routines.NotifyAction
import com.buntap.app.routines.PressKeyAction
import com.buntap.app.routines.RepeatAction
import com.buntap.app.routines.RetryAction
import com.buntap.app.routines.RoutineAction
import com.buntap.app.routines.RoutineDefaults
import com.buntap.app.routines.RoutineDescriber
import com.buntap.app.routines.RoutineTree
import com.buntap.app.routines.RoutineValidator
import com.buntap.app.routines.StopAction
import com.buntap.app.routines.TapPointAction
import com.buntap.app.routines.TapSpec
import com.buntap.app.routines.TapTargetAction
import com.buntap.app.routines.TimeLimitAction
import com.buntap.app.routines.TypeTextAction
import com.buntap.app.routines.WaitAction
import com.buntap.app.routines.WaitForTargetAction
import com.buntap.app.routines.WaitUntilGoneAction
import com.buntap.app.ui.components.Banner
import com.buntap.app.ui.components.BannerTone
import com.buntap.app.ui.components.DropdownField
import com.buntap.app.ui.components.Fmt
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.IntField
import com.buntap.app.ui.components.OptionalSecondsField
import com.buntap.app.ui.components.RateEditor
import com.buntap.app.ui.components.SecondsField
import com.buntap.app.ui.components.SegmentedChoice
import com.buntap.app.ui.components.SliderField
import com.buntap.app.ui.components.SwitchRow

/** Lists all action types; tapping one creates it. */
@Composable
fun ActionTypePicker(onPick: (ActionType) -> Unit, onDismiss: () -> Unit, hasTargets: Boolean) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add action") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(ActionType.entries) { type ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onPick(type) }.padding(vertical = 10.dp),
                    ) {
                        Text(type.title, style = MaterialTheme.typography.bodyLarge)
                        HelpText(type.description + if (type.usesTarget && !hasTargets) " (create a visual target first)" else "")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Edits one action. The draft lives in the ViewModel ([onChange] after every edit) so that a
 * coordinate picked on screen while BunTap is in the background lands in the same draft.
 */
@Composable
fun ActionEditorDialog(
    draft: RoutineAction,
    isNew: Boolean,
    targets: List<VisualTarget>,
    defaults: RoutineDefaults,
    limits: RateLimits,
    geometry: ScreenGeometry,
    captureActive: Boolean,
    onChange: (RoutineAction) -> Unit,
    onPickOnScreen: () -> Unit,
    onPickOnScreenshot: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val targetOptions = targets.map { it.id to it.name }
    val issues = RoutineValidator.validate(
        "x",
        com.buntap.app.routines.RoutineBody(defaults = defaults, actions = listOf(draftForValidation(draft))),
        targets.map { it.id }.toSet(),
        limits,
        geometry,
    ).filter { it.actionId == draft.id }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.95f)) {
            Column(Modifier.padding(20.dp)) {
                // Fields scroll; the action buttons below stay visible.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(draft), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text((if (isNew) "New: " else "") + RoutineDescriber.title(draft), style = MaterialTheme.typography.titleLarge)
                }

                when (val a = draft) {
                    is WaitAction -> SecondsField("Duration", a.durationMs, { onChange(a.copy(durationMs = it)) }, 0L..RoutineValidator.MAX_WAIT_MS)

                    is TapPointAction -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IntField("X", a.x, { onChange(a.copy(x = it)) }, 0..20_000, Modifier.weight(1f), suffix = "px")
                            IntField("Y", a.y, { onChange(a.copy(y = it)) }, 0..20_000, Modifier.weight(1f), suffix = "px")
                        }
                        CoordinateValidator.check(a.x, a.y, geometry)?.let { Banner(it, BannerTone.ERROR) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onPickOnScreen) { Text("Pick on screen") }
                            if (captureActive) OutlinedButton(onClick = onPickOnScreenshot) { Text("On screenshot") }
                        }
                        TapSpecEditor(a.taps, defaults, limits) { onChange(a.copy(taps = it)) }
                        SecondsField("Delay before", a.preDelayMs, { onChange(a.copy(preDelayMs = it)) }, 0L..RoutineValidator.MAX_WAIT_MS)
                        SecondsField("Delay after", a.postDelayMs, { onChange(a.copy(postDelayMs = it)) }, 0L..RoutineValidator.MAX_WAIT_MS)
                        OptionalSecondsField(
                            "Timeout", "Fail if the taps take longer than this.", a.timeoutMs, { onChange(a.copy(timeoutMs = it)) },
                            100L..RoutineValidator.MAX_TIMEOUT_MS, 10_000L, "Timeout",
                        )
                    }

                    is FindTargetAction -> {
                        TargetChooser(targetOptions, a.targetId) { onChange(a.copy(targetId = it)) }
                        TimeoutEditor(a.timeoutMs, defaults, "How long to look") { onChange(a.copy(timeoutMs = it)) }
                    }

                    is WaitForTargetAction -> {
                        TargetChooser(targetOptions, a.targetId) { onChange(a.copy(targetId = it)) }
                        TimeoutEditor(a.timeoutMs, defaults, "Maximum wait") { onChange(a.copy(timeoutMs = it)) }
                    }

                    is WaitUntilGoneAction -> {
                        TargetChooser(targetOptions, a.targetId) { onChange(a.copy(targetId = it)) }
                        TimeoutEditor(a.timeoutMs, defaults, "Maximum wait") { onChange(a.copy(timeoutMs = it)) }
                    }

                    is TapTargetAction -> {
                        TargetChooser(targetOptions, a.targetId) { onChange(a.copy(targetId = it)) }
                        SwitchRow(
                            "Reuse last detection",
                            a.useLastDetection,
                            { onChange(a.copy(useLastDetection = it)) },
                            "Use the position found by an earlier Find / Wait / If step instead of searching again.",
                        )
                        TimeoutEditor(a.timeoutMs, defaults, "How long to look") { onChange(a.copy(timeoutMs = it)) }
                        TapSpecEditor(a.taps, defaults, limits) { onChange(a.copy(taps = it)) }
                        SegmentedChoice(
                            listOf(true to "Target's tap setting", false to "Custom point"),
                            a.anchor == null,
                            { own -> onChange(a.copy(anchor = if (own) null else TapAnchor(0.5f, 0.25f))) },
                        )
                        a.anchor?.let { anchor ->
                            SliderField("Horizontal position", anchor.fx, { onChange(a.copy(anchor = anchor.copy(fx = it))) }, 0f..1f, { Fmt.percent(it) })
                            SliderField("Vertical position", anchor.fy, { onChange(a.copy(anchor = anchor.copy(fy = it))) }, 0f..1f, { Fmt.percent(it) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IntField("Offset X", a.offsetX, { onChange(a.copy(offsetX = it)) }, -RoutineValidator.MAX_OFFSET..RoutineValidator.MAX_OFFSET, Modifier.weight(1f), suffix = "px")
                            IntField("Offset Y", a.offsetY, { onChange(a.copy(offsetY = it)) }, -RoutineValidator.MAX_OFFSET..RoutineValidator.MAX_OFFSET, Modifier.weight(1f), suffix = "px")
                        }
                        SecondsField("Delay before", a.preDelayMs, { onChange(a.copy(preDelayMs = it)) }, 0L..RoutineValidator.MAX_WAIT_MS)
                        SecondsField("Delay after", a.postDelayMs, { onChange(a.copy(postDelayMs = it)) }, 0L..RoutineValidator.MAX_WAIT_MS)
                    }

                    is PressKeyAction -> {
                        DropdownField(
                            "Key",
                            GlobalKey.entries.map { k ->
                                k to k.label + if (Build.VERSION.SDK_INT < k.minSdk) " (needs Android API ${k.minSdk})" else ""
                            },
                            a.key,
                            { onChange(a.copy(key = it)) },
                        )
                        HelpText("Android does not let apps inject arbitrary key presses; these are the system actions available to accessibility services.")
                    }

                    is TypeTextAction -> {
                        OutlinedTextField(
                            value = a.text,
                            onValueChange = { onChange(a.copy(text = it.take(RoutineValidator.MAX_TEXT_LENGTH))) },
                            label = { Text("Text") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SwitchRow("Append", a.append, { onChange(a.copy(append = it)) }, "Add to the existing text instead of replacing it.")
                        Banner("Routines are stored and exported as plain text. Never put passwords or other secrets here.", BannerTone.WARNING)
                        HelpText("A text field must be focused (tap it first). Typed text is never written to logs.")
                    }

                    is RepeatAction -> {
                        IntField("Repeat count", a.times, { onChange(a.copy(times = it)) }, 1..RoutineValidator.MAX_REPEAT_TIMES)
                        HelpText("Add the steps to repeat inside the group in the routine list.")
                    }

                    is IfTargetAction -> {
                        TargetChooser(targetOptions, a.targetId) { onChange(a.copy(targetId = it)) }
                        SegmentedChoice(listOf(true to "Exists", false to "Does not exist"), a.expectPresent, { onChange(a.copy(expectPresent = it)) })
                        TimeoutEditor(a.timeoutMs, defaults, "How long to look") { onChange(a.copy(timeoutMs = it)) }
                        HelpText("Add steps to the Then and Else branches in the routine list.")
                    }

                    is ContinueIfTargetAction -> {
                        TargetChooser(targetOptions, a.targetId) { onChange(a.copy(targetId = it)) }
                        TimeoutEditor(a.timeoutMs, defaults, "How long to look") { onChange(a.copy(timeoutMs = it)) }
                        DropdownField("If missing", MissingTargetBehavior.entries.map { it to it.label }, a.whenMissing, { onChange(a.copy(whenMissing = it)) })
                    }

                    is RetryAction -> {
                        IntField("Maximum attempts", a.maxAttempts, { onChange(a.copy(maxAttempts = it)) }, 1..RoutineValidator.MAX_RETRY_ATTEMPTS, supporting = "Total attempts, including the first one.")
                        SecondsField("Delay between attempts", a.delayBetweenMs, { onChange(a.copy(delayBetweenMs = it)) }, 0L..RoutineValidator.MAX_WAIT_MS)
                    }

                    is TimeLimitAction -> {
                        SecondsField("Time limit", a.timeoutMs, { onChange(a.copy(timeoutMs = it)) }, 100L..RoutineValidator.MAX_TIMEOUT_MS)
                        HelpText("When the limit expires, the steps inside are cancelled and this action fails (see “On failure”).")
                    }

                    is StopAction -> {
                        SegmentedChoice(listOf(true to "Success", false to "Failure"), a.success, { onChange(a.copy(success = it)) })
                        OutlinedTextField(a.message, { onChange(a.copy(message = it.take(RoutineValidator.MAX_DESCRIPTION_LENGTH))) }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                    }

                    is NotifyAction -> {
                        OutlinedTextField(a.title, { onChange(a.copy(title = it.take(RoutineValidator.MAX_NAME_LENGTH))) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(a.message, { onChange(a.copy(message = it.take(RoutineValidator.MAX_DESCRIPTION_LENGTH))) }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                        HelpText("Requires notification permission; otherwise the message is only logged.")
                    }

                    is LogAction -> OutlinedTextField(
                        a.message,
                        { onChange(a.copy(message = it.take(RoutineValidator.MAX_DESCRIPTION_LENGTH))) },
                        label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow("Enabled", draft.enabled, { onChange(RoutineTree.withCommon(draft, enabled = it)) }, "Disabled actions are skipped.")
                DropdownField(
                    "On failure",
                    listOf(FailurePolicy.STOP_ROUTINE to "Stop the routine (or retry in a Retry group)", FailurePolicy.CONTINUE to "Continue with the next action"),
                    draft.onFailure,
                    { onChange(RoutineTree.withCommon(draft, onFailure = it)) },
                )
                OutlinedTextField(
                    draft.note,
                    { onChange(RoutineTree.withCommon(draft, note = it.take(RoutineValidator.MAX_DESCRIPTION_LENGTH))) },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                issues.forEach { Banner(it.message, BannerTone.ERROR) }
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = onSave) { Text(if (isNew) "Add" else "Save") }
                }
            }
        }
    }
}

/** Groups are validated without their children here (children are edited in the list). */
private fun draftForValidation(a: RoutineAction): RoutineAction = when (a) {
    is RepeatAction -> if (a.actions.isEmpty()) a.copy(actions = listOf(WaitAction("_child", 0))) else a
    is RetryAction -> if (a.actions.isEmpty()) a.copy(actions = listOf(WaitAction("_child", 0))) else a
    is TimeLimitAction -> if (a.actions.isEmpty()) a.copy(actions = listOf(WaitAction("_child", 0))) else a
    is IfTargetAction -> if (a.thenActions.isEmpty() && a.elseActions.isEmpty()) a.copy(thenActions = listOf(WaitAction("_child", 0))) else a
    else -> a
}

@Composable
private fun TargetChooser(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    if (options.isEmpty()) {
        Banner("You have no visual targets yet. Create one on the Targets tab.", BannerTone.WARNING)
    } else {
        DropdownField("Visual target", options, selected.takeIf { id -> options.any { it.first == id } }, onSelect, isError = options.none { it.first == selected })
    }
}

@Composable
private fun TimeoutEditor(valueMs: Long?, defaults: RoutineDefaults, label: String, onChange: (Long?) -> Unit) {
    SwitchRow(
        "Use routine default",
        valueMs == null,
        { useDefault -> onChange(if (useDefault) null else defaults.timeoutMs) },
        "Default: ${if (defaults.timeoutMs <= 0) "one scan" else Fmt.millis(defaults.timeoutMs)}",
    )
    if (valueMs != null) {
        SecondsField(label, valueMs, onChange, 0L..RoutineValidator.MAX_TIMEOUT_MS, supporting = "0 = check a single frame.")
    }
}

@Composable
private fun TapSpecEditor(spec: TapSpec, defaults: RoutineDefaults, limits: RateLimits, onChange: (TapSpec) -> Unit) {
    SwitchRow("Own tap count", spec.count != null, { on -> onChange(spec.copy(count = if (on) defaults.tapCount else null)) }, "Default: ${defaults.tapCount}")
    spec.count?.let { IntField("Taps", it, { v -> onChange(spec.copy(count = v)) }, 1..RoutineValidator.MAX_TAP_COUNT) }
    SwitchRow("Own rate", spec.rate != null, { on -> onChange(spec.copy(rate = if (on) defaults.rate else null)) }, "Default: ${Fmt.tps(defaults.rate.tps)} TPS")
    spec.rate?.let { RateEditor(it, limits, { r -> onChange(spec.copy(rate = r)) }) }
}

